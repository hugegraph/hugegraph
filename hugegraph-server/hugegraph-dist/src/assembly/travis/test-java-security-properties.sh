#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

SERVER_ROOT_INPUT="${1:?Usage: $0 PATH_TO_SERVER_DIST}"
SERVER_ROOT=$(cd "$SERVER_ROOT_INPUT" && pwd)
SERVER_SCRIPT="${SERVER_ROOT}/bin/hugegraph-server.sh"
CONF="${SERVER_ROOT}/conf"
SECURITY_PROPERTIES="${CONF}/java-security.properties"

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

assert_argument() {
    local argument="$1"
    local capture="$2"
    grep -Fxq -- "$argument" "$capture" || \
        fail "missing JVM argument: $argument"
}

assert_no_argument() {
    local pattern="$1"
    local capture="$2"
    if grep -Eq -- "$pattern" "$capture"; then
        fail "unexpected JVM argument matching: $pattern"
    fi
}

if [[ ! -x "$SERVER_SCRIPT" ]]; then
    fail "server script is not executable: $SERVER_SCRIPT"
fi
if [[ ! -f "$SECURITY_PROPERTIES" ]]; then
    fail "security properties file is missing: $SECURITY_PROPERTIES"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
else
    JAVA_BIN="java"
fi

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

CHECK_SOURCE="${TEMP_DIR}/ReadDnsCacheTtl.java"
cat > "$CHECK_SOURCE" <<'JAVA'
import java.security.Security;

public class ReadDnsCacheTtl {
    public static void main(String[] args) {
        System.out.print(Security.getProperty("networkaddress.cache.ttl"));
    }
}
JAVA

ACTUAL_TTL=$("$JAVA_BIN" \
    -Djava.security.properties="$SECURITY_PROPERTIES" "$CHECK_SOURCE")
if [[ "$ACTUAL_TTL" != "30" ]]; then
    fail "expected security property TTL 30, got: $ACTUAL_TTL"
fi

SYSTEM_PROPERTY_TTL=$("$JAVA_BIN" \
    -Dnetworkaddress.cache.ttl=99 \
    -Djava.security.properties="$SECURITY_PROPERTIES" "$CHECK_SOURCE")
if [[ "$SYSTEM_PROPERTY_TTL" != "30" ]]; then
    fail "ordinary -D property unexpectedly changed the security property"
fi

OPERATOR_PROPERTIES="${TEMP_DIR}/operator-security.properties"
echo "networkaddress.cache.ttl=45" > "$OPERATOR_PROPERTIES"
OPERATOR_TTL=$("$JAVA_BIN" \
    -Djava.security.properties="$SECURITY_PROPERTIES" \
    -Djava.security.properties="$OPERATOR_PROPERTIES" "$CHECK_SOURCE")
if [[ "$OPERATOR_TTL" != "45" ]]; then
    fail "operator security properties override was not honored"
fi

MOCK_JAVA_HOME="${TEMP_DIR}/mock-java-home"
mkdir -p "${MOCK_JAVA_HOME}/bin"
cat > "${MOCK_JAVA_HOME}/bin/java" <<'MOCK'
#!/bin/bash
if [[ " $* " == *" -version "* ]]; then
    echo 'openjdk version "11.0.0"' >&2
    exit 0
fi
printf '%s\n' "$@" > "$CAPTURE_FILE"
MOCK
chmod +x "${MOCK_JAVA_HOME}/bin/java"

ENABLED_CAPTURE="${TEMP_DIR}/enabled.args"
CAPTURE_FILE="$ENABLED_CAPTURE" JAVA_HOME="$MOCK_JAVA_HOME" \
    STDOUT_MODE=true "$SERVER_SCRIPT" \
    "${CONF}/gremlin-server.yaml" "${CONF}/rest-server.properties" true \
    "-Doperator.marker=preserved" >/dev/null

assert_argument \
    "-Djava.security.properties=${SECURITY_PROPERTIES}" "$ENABLED_CAPTURE"
assert_argument \
    "-Djava.security.manager=org.apache.hugegraph.security.HugeSecurityManager" \
    "$ENABLED_CAPTURE"
assert_argument "-Doperator.marker=preserved" "$ENABLED_CAPTURE"
assert_no_argument '^-D(networkaddress\.cache\.ttl|sun\.net\.inetaddr\.ttl)=' \
                   "$ENABLED_CAPTURE"

DISABLED_CAPTURE="${TEMP_DIR}/disabled.args"
CAPTURE_FILE="$DISABLED_CAPTURE" JAVA_HOME="$MOCK_JAVA_HOME" \
    STDOUT_MODE=true "$SERVER_SCRIPT" \
    "${CONF}/gremlin-server.yaml" "${CONF}/rest-server.properties" false \
    "-Doperator.marker=preserved" >/dev/null

assert_no_argument '^-Djava\.security\.properties=' "$DISABLED_CAPTURE"
assert_no_argument '^-Djava\.security\.manager=' "$DISABLED_CAPTURE"
assert_argument "-Doperator.marker=preserved" "$DISABLED_CAPTURE"

OVERRIDE_CAPTURE="${TEMP_DIR}/override.args"
CAPTURE_FILE="$OVERRIDE_CAPTURE" JAVA_HOME="$MOCK_JAVA_HOME" \
    STDOUT_MODE=true "$SERVER_SCRIPT" \
    "${CONF}/gremlin-server.yaml" "${CONF}/rest-server.properties" true \
    "-Djava.security.properties=${OPERATOR_PROPERTIES}" >/dev/null

LAST_SECURITY_ARGUMENT=$(grep -E '^-Djava\.security\.properties=' \
                         "$OVERRIDE_CAPTURE" | tail -n 1)
if [[ "$LAST_SECURITY_ARGUMENT" != \
      "-Djava.security.properties=${OPERATOR_PROPERTIES}" ]]; then
    fail "operator security properties argument was overwritten"
fi

echo "PASS: Java security properties and startup wiring"
