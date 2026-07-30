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
#
# Smoke tests for docker-entrypoint.sh init-store lifecycle.
#
# The entrypoint is run against a throwaway install tree whose ./bin scripts are
# stubs recording their own invocation, so the tests assert on which scripts ran
# and on the resulting config without a backend or Docker. A source-file Java
# helper verifies password values with the same properties parser semantics.
#
# Usage: hugegraph-server/hugegraph-dist/docker/test/test-docker-entrypoint.sh

set -uo pipefail

SELF_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENTRYPOINT="${SELF_DIR}/../docker-entrypoint.sh"
TEST_CLASSES=$(mktemp -d "${TMPDIR:-/tmp}/hg-entrypoint-classes.XXXXXX")
javac -d "${TEST_CLASSES}" "${SELF_DIR}/JavaPropertiesReader.java" \
      "${SELF_DIR}/JavaPropertiesTool.java"

PASS=0
FAIL=0

fail() {
    echo "    FAIL: $*"
    FAIL=$((FAIL + 1))
}

ok() {
    PASS=$((PASS + 1))
}

assert_ran() {
    if grep -qxF "$1" "${INSTALL}/calls.log" 2>/dev/null; then
        ok
    else
        fail "expected '$1' to run; calls were: $(tr '\n' ' ' < "${INSTALL}/calls.log")"
    fi
}

assert_not_ran() {
    if grep -qxF "$1" "${INSTALL}/calls.log" 2>/dev/null; then
        fail "expected '$1' NOT to run"
    else
        ok
    fi
}

assert_file() {
    if [[ -f "${INSTALL}/$1" ]]; then ok; else fail "expected file '$1' to exist"; fi
}

assert_no_file() {
    if [[ -f "${INSTALL}/$1" ]]; then fail "expected file '$1' NOT to exist"; else ok; fi
}

assert_output_contains() {
    if grep -qF "$1" "${INSTALL}/out.log" 2>/dev/null; then
        ok
    else
        fail "expected output to contain '$1': $(cat "${INSTALL}/out.log")"
    fi
}

assert_prop() {
    local expected="$1=$2"
    if grep -qxF "${expected}" "${INSTALL}/conf/rest-server.properties" 2>/dev/null; then
        ok
    else
        fail "expected property '${expected}' in rest-server.properties"
    fi
}

bytes_hex() {
    printf '%s' "$1" | od -An -v -t x1 | tr -d '[:space:]'
}

file_mode() {
    stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"
}

file_mtime() {
    stat -c '%Y' "$1" 2>/dev/null || stat -f '%m' "$1"
}

# Read the generated property through java.util.Properties and compare UTF-8
# bytes. This catches physical-line truncation and every escape-sequence error,
# including trailing newlines that command substitution would otherwise hide.
assert_prop_round_trip() {
    local key="$1" expected="$2" actual_hex expected_hex
    if ! actual_hex=$(java -cp "${TEST_CLASSES}" JavaPropertiesReader \
            "${INSTALL}/conf/rest-server.properties" "${key}" \
            2> "${INSTALL}/java-properties.err"); then
        fail "Java could not read '${key}': $(cat "${INSTALL}/java-properties.err")"
        return
    fi
    expected_hex=$(bytes_hex "${expected}")
    if [[ "${actual_hex}" == "${expected_hex}" ]]; then
        ok
    else
        fail "'${key}' parsed as hex '${actual_hex}', expected '${expected_hex}'"
    fi
}

# A scalar option must end up defined exactly once, on any separator, or the
# properties parser exposes it as a list and a scalar read of it fails
assert_prop_defined_once() {
    local n
    n=$(grep -cE "^[[:space:]]*$1([[:space:]]*[=:]|[[:space:]])" \
        "${INSTALL}/conf/rest-server.properties" 2>/dev/null || true)
    if [[ "${n}" == "1" ]]; then ok; else fail "expected '$1' defined once, found ${n}"; fi
}

# Matches the separator set assert_prop_defined_once uses, so a `key:value` or
# `key value` definition cannot pass as absent
assert_no_prop_key() {
    if grep -qE "^[[:space:]]*$1([[:space:]]*[=:]|[[:space:]])" \
            "${INSTALL}/conf/rest-server.properties" 2>/dev/null; then
        fail "expected no '$1' property"
    else
        ok
    fi
}

# Auth is only fully enabled when all three configs agree: the REST properties,
# the gremlin-server.yaml authentication block and the graph's auth proxy
assert_auth_fully_enabled() {
    local n
    n=$(grep -cE '^[[:space:]]*authentication:' \
        "${INSTALL}/conf/gremlin-server.yaml" 2>/dev/null || true)
    if [[ "${n}" == "1" ]]; then
        ok
    else
        fail "expected one gremlin-server.yaml authentication block, found ${n}"
    fi
    if grep -qxF "gremlin.graph=org.apache.hugegraph.auth.HugeFactoryAuthProxy" \
            "${INSTALL}/conf/graphs/hugegraph.properties" 2>/dev/null; then
        ok
    else
        fail "expected hugegraph.properties to use HugeFactoryAuthProxy"
    fi
    assert_prop_defined_once "auth.authenticator"
    assert_prop_defined_once "auth.graph_store"
}

# Build a throwaway install tree with stubbed bin scripts
new_install() {
    INSTALL=$(mktemp -d "${TMPDIR:-/tmp}/hg-entrypoint-test.XXXXXX")
    mkdir -p "${INSTALL}/bin" "${INSTALL}/conf/graphs"

    # Mirrors the shipped conf: the auth properties are present but commented
    cat > "${INSTALL}/conf/rest-server.properties" <<'EOF'
restserver.url=http://0.0.0.0:8080
graphs=./conf/graphs
#auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator
#auth.admin_pa=pa
EOF
    chmod 644 "${INSTALL}/conf/rest-server.properties"
    cat > "${INSTALL}/conf/graphs/hugegraph.properties" <<'EOF'
backend=rocksdb
gremlin.graph=org.apache.hugegraph.HugeFactory
EOF
    # Shipped without an authentication block, which is what enable-auth.sh adds
    echo "host: 0.0.0.0" > "${INSTALL}/conf/gremlin-server.yaml"

    local script
    for script in wait-storage start-hugegraph wait-partition; do
        cat > "${INSTALL}/bin/${script}.sh" <<EOF
#!/bin/bash
echo "${script}.sh" >> "${INSTALL}/calls.log"
EOF
        chmod +x "${INSTALL}/bin/${script}.sh"
    done

    # Records whether a password was piped in, which is how the entrypoint
    # passes a Docker PASSWORD to the admin bootstrap
    cat > "${INSTALL}/bin/init-store.sh" <<EOF
#!/bin/bash
echo "init-store.sh" >> "${INSTALL}/calls.log"
if [[ \$# -gt 0 ]]; then
    echo "init-store.sh:args=\$*" >> "${INSTALL}/calls.log"
fi
if [[ ! -t 0 ]]; then
    stdin=\$(cat)
    [[ -n "\${stdin}" ]] && echo "init-store.sh:stdin=\${stdin}" >> "${INSTALL}/calls.log"
fi
exit "\${INIT_STORE_STUB_RC:-0}"
EOF
    chmod +x "${INSTALL}/bin/init-store.sh"

    # The production wrapper launches ConfigTool from the assembled jars. This
    # source-launch stub keeps the shell suite backend-free while using Java's
    # properties grammar for escaped keys and continued logical lines.
    cat > "${INSTALL}/bin/config-tool.sh" <<EOF
#!/bin/bash
if [[ "\$1" == "validate-skip" ]]; then
    exit "\${INIT_STORE_STUB_RC:-0}"
fi
if [[ "\$1" == "requires-local-admin" ]]; then
    authenticator=\$(java -cp "${TEST_CLASSES}" JavaPropertiesTool get "\$2" \
        auth.authenticator)
    remote_url=\$(java -cp "${TEST_CLASSES}" JavaPropertiesTool get "\$2" \
        auth.remote_url)
    [[ "\${authenticator}" == \
       "org.apache.hugegraph.auth.StandardAuthenticator" && \
       -z "\${remote_url}" ]]
    exit
fi
exec java -cp "${TEST_CLASSES}" JavaPropertiesTool "\$@"
EOF
    chmod +x "${INSTALL}/bin/config-tool.sh"

    # Mirrors bin/enable-auth.sh: appends the REST keys and the YAML
    # authentication block and switches the graph to the auth proxy, but only
    # before its one-time conf-bak guard exists.
    cat > "${INSTALL}/bin/enable-auth.sh" <<EOF
#!/bin/bash
echo "enable-auth.sh" >> "${INSTALL}/calls.log"
if [[ ! -d "${INSTALL}/conf-bak" ]]; then
mkdir -p "${INSTALL}/conf-bak"
{
    echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator"
    echo "auth.graph_store=hugegraph"
} >> "${INSTALL}/conf/rest-server.properties"
cat >> "${INSTALL}/conf/gremlin-server.yaml" <<'YAML'
authentication: {
  authenticator: org.apache.hugegraph.auth.StandardAuthenticator,
  authenticationHandler: org.apache.hugegraph.auth.WsAndHttpBasicAuthHandler,
  config: {tokens: conf/rest-server.properties}
}
YAML
sed -i.bak 's/gremlin.graph=org.apache.hugegraph.HugeFactory/gremlin.graph=org.apache.hugegraph.auth.HugeFactoryAuthProxy/g' \
    "${INSTALL}/conf/graphs/hugegraph.properties"
rm -f "${INSTALL}/conf/graphs/hugegraph.properties.bak"
fi
EOF
    chmod +x "${INSTALL}/bin/enable-auth.sh"

    : > "${INSTALL}/calls.log"
}

# The built-in admin created on the PD path is usable only when the auth graph
# selects HStore's PD-backed auth manager.
enable_pd() {
    echo "usePD=true" >> "${INSTALL}/conf/rest-server.properties"
    sed -i.bak 's/^backend=rocksdb$/backend=hstore/' \
        "${INSTALL}/conf/graphs/hugegraph.properties"
    rm -f "${INSTALL}/conf/graphs/hugegraph.properties.bak"
}

# Run the entrypoint inside the throwaway tree. No ./bin/pid is ever written by
# the stubs, so the entrypoint's tail-on-pid block is skipped and it returns.
run_entrypoint() {
    ( cd "${INSTALL}" && env "$@" bash "${ENTRYPOINT}" ) > "${INSTALL}/out.log" 2>&1
    local rc=$?
    if [[ ${rc} -ne 0 ]]; then
        fail "entrypoint exited ${rc}; output: $(cat "${INSTALL}/out.log")"
    fi
    return 0
}

run_entrypoint_fails() {
    if ( cd "${INSTALL}" && env "$@" bash "${ENTRYPOINT}" ) \
            > "${INSTALL}/out.log" 2>&1; then
        fail "expected entrypoint to fail"
    else
        ok
    fi
}

cleanup() { [[ -n "${INSTALL:-}" ]] && rm -rf "${INSTALL}"; }
cleanup_all() {
    cleanup
    rm -rf "${TEST_CLASSES}"
}
trap cleanup_all EXIT

echo "==> default: no flag set, full init runs"
new_install
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_ran "wait-storage.sh"
assert_ran "init-store.sh"
assert_not_ran "enable-auth.sh"
assert_file "docker/init_complete"
cleanup

echo "==> default + PASSWORD: auth enabled, password piped to init-store"
new_install
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret
assert_ran "enable-auth.sh"
assert_ran "init-store.sh"
assert_ran "init-store.sh:stdin=s3cret"
assert_file "docker/init_complete"
assert_auth_fully_enabled
cleanup

echo "==> skip via env: InitStore validates the no-op and no flag is written"
new_install
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "wait-storage.sh"
assert_ran "init-store.sh"
assert_prop "init_store.enabled" "false"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip + PASSWORD: password reaches auth.admin_pa, not init-store stdin"
new_install
enable_pd
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_ran "enable-auth.sh"
assert_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop_round_trip "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip via mounted property only: env var absent behaves the same"
new_install
enable_pd
echo "init_store.enabled=false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret
assert_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop_round_trip "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> env wins over a conflicting mounted property"
new_install
echo "init_store.enabled=false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=true
assert_ran "init-store.sh"
assert_file "docker/init_complete"
cleanup

echo "==> false then true: a restart with init enabled still initializes"
new_install
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "init-store.sh"
assert_no_file "docker/init_complete"
: > "${INSTALL}/calls.log"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=true
assert_ran "init-store.sh"
assert_file "docker/init_complete"
cleanup

echo "==> restart with init enabled: the flag file suppresses re-init"
new_install
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_ran "init-store.sh"
: > "${INSTALL}/calls.log"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_not_ran "init-store.sh"
assert_file "docker/init_complete"
cleanup

echo "==> adding PASSWORD after no-auth init still creates the admin"
new_install
mkdir -p "${INSTALL}/docker"
touch "${INSTALL}/docker/init_complete"
run_entrypoint PASSWORD=s3cret
assert_ran "enable-auth.sh"
assert_ran "init-store.sh"
assert_ran "init-store.sh:stdin=s3cret"
assert_auth_fully_enabled
assert_file "docker/init_complete"
assert_file "docker/auth_init_state"
cleanup

echo "==> mounted built-in auth after no-auth init still creates the admin"
new_install
mkdir -p "${INSTALL}/docker"
touch "${INSTALL}/docker/init_complete"
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint PASSWORD=s3cret
assert_ran "init-store.sh"
assert_ran "init-store.sh:stdin=s3cret"
assert_auth_fully_enabled
assert_file "docker/auth_init_state"
cleanup

echo "==> mounted built-in auth without PASSWORD uses an explicit admin password"
new_install
mkdir -p "${INSTALL}/docker"
touch "${INSTALL}/docker/init_complete"
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
echo "auth.admin_pa=s3cret" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD
assert_ran "init-store.sh"
assert_ran "init-store.sh:args=--use-configured-admin-password"
assert_auth_fully_enabled
assert_file "docker/auth_init_state"
: > "${INSTALL}/calls.log"
run_entrypoint -u PASSWORD
assert_not_ran "init-store.sh"
assert_ran "start-hugegraph.sh"
cleanup

echo "==> an existing conf-bak cannot suppress requested auth"
new_install
mkdir -p "${INSTALL}/conf-bak"
run_entrypoint PASSWORD=s3cret
assert_ran "enable-auth.sh"
assert_ran "init-store.sh:stdin=s3cret"
assert_auth_fully_enabled
cleanup

echo "==> uppercase FALSE is honoured, matching the server's boolean parsing"
new_install
enable_pd
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=FALSE PASSWORD=s3cret
assert_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop "init_store.enabled" "false"
assert_prop_round_trip "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> 'off' and 'no' are honoured too"
for value in off no; do
    new_install
    run_entrypoint -u PASSWORD "HG_SERVER_INIT_STORE_ENABLED=${value}"
    assert_ran "init-store.sh"
    assert_no_file "docker/init_complete"
    cleanup
done

echo "==> a non-boolean value fails fast instead of diverging"
new_install
if ( cd "${INSTALL}" && env -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=maybe \
        bash "${ENTRYPOINT}" ) >/dev/null 2>&1; then
    fail "expected a non-boolean HG_SERVER_INIT_STORE_ENABLED to fail"
else
    ok
fi
assert_not_ran "start-hugegraph.sh"
cleanup

echo "==> mounted property with a ':' separator is honoured"
new_install
echo "init_store.enabled:false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_ran "init-store.sh"
assert_no_file "docker/init_complete"
cleanup

echo "==> a continued mounted boolean is parsed as one Java property"
new_install
{
    printf 'init_store.enabled=fal\\\n'
    echo '  se'
} >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_ran "init-store.sh"
assert_no_file "docker/init_complete"
cleanup

echo "==> password with backslashes survives the properties round trip"
new_install
enable_pd
run_entrypoint 'PASSWORD=abc\def' HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "init-store.sh"
assert_prop_round_trip "auth.admin_pa" 'abc\def'
cleanup

echo "==> properties metacharacters, controls and UTF-8 round-trip exactly"
new_install
enable_pd
complex_password=$' \tmeta:=#!\\\r\f\np\xc3\xa4ss\xe9\x9b\xaa'
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false \
    "PASSWORD=${complex_password}"
assert_prop_round_trip "auth.admin_pa" "${complex_password}"
assert_prop_defined_once "auth.admin_pa"
cleanup

echo "==> a trailing newline survives the properties round trip"
new_install
enable_pd
trailing_newline_password=$'ends-with-newline\n'
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false \
    "PASSWORD=${trailing_newline_password}"
assert_prop_round_trip "auth.admin_pa" "${trailing_newline_password}"
cleanup

echo "==> a Java validation failure is propagated before password persistence"
new_install
run_entrypoint_fails -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret \
    HG_SERVER_INIT_STORE_ENABLED=false INIT_STORE_STUB_RC=1
assert_not_ran "start-hugegraph.sh"
assert_not_ran "init-store.sh"
assert_no_prop_key "auth.admin_pa"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip with auth already in a mounted config, no usePD, is refused too"
new_install
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint_fails -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false \
    INIT_STORE_STUB_RC=1
assert_not_ran "start-hugegraph.sh"
assert_not_ran "init-store.sh"
cleanup

echo "==> skip without auth is unaffected by the usePD requirement"
new_install
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "start-hugegraph.sh"
assert_ran "init-store.sh"
assert_no_file "docker/init_complete"
cleanup

echo "==> env override of a colon-form property leaves one canonical key"
new_install
echo "init_store.enabled:false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=true
assert_prop_defined_once "init_store.enabled"
assert_prop "init_store.enabled" "true"
assert_ran "init-store.sh"
cleanup

echo "==> env override of a whitespace-form property leaves one canonical key"
new_install
echo "init_store.enabled false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=true
assert_prop_defined_once "init_store.enabled"
assert_prop "init_store.enabled" "true"
assert_ran "init-store.sh"
cleanup

echo "==> PASSWORD override of a colon-form auth.admin_pa leaves one key"
new_install
enable_pd
echo "auth.admin_pa:old" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_prop_defined_once "auth.admin_pa"
assert_prop_round_trip "auth.admin_pa" "s3cret"
cleanup

echo "==> commented-out defaults are not treated as definitions"
new_install
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret
# The shipped file ships '#auth.admin_pa=pa' commented; it must stay commented
# and must not count as an existing definition
if grep -qxF "#auth.admin_pa=pa" "${INSTALL}/conf/rest-server.properties"; then
    ok
else
    fail "expected the commented '#auth.admin_pa=pa' line to be preserved"
fi
cleanup

echo "==> mounted config that already enables auth is not duplicated"
new_install
enable_pd
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
echo "auth.graph_store=hugegraph" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_not_ran "enable-auth.sh"
assert_prop_defined_once "auth.admin_pa"
assert_prop_round_trip "auth.admin_pa" "s3cret"
# The mounted config carried only the REST keys, so the YAML block and the auth
# proxy still have to be applied, or Gremlin would stay unauthenticated
assert_auth_fully_enabled
cleanup

echo "==> mounted config with only auth.authenticator still authenticates Gremlin"
new_install
enable_pd
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_not_ran "enable-auth.sh"
assert_auth_fully_enabled
assert_prop "auth.graph_store" "hugegraph"
cleanup

echo "==> mounted auth without PASSWORD still protects Gremlin and the graph"
new_install
enable_pd
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
echo "auth.admin_pa=s3cret" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "init-store.sh"
assert_auth_fully_enabled
assert_prop_round_trip "auth.admin_pa" "s3cret"
cleanup

echo "==> a gremlin-server.yaml without a trailing newline is still valid"
new_install
enable_pd
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
printf 'host: 0.0.0.0' > "${INSTALL}/conf/gremlin-server.yaml"
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_auth_fully_enabled
# The appended block must start on its own line, not glued onto 'host: 0.0.0.0'
if grep -qxF "host: 0.0.0.0" "${INSTALL}/conf/gremlin-server.yaml"; then
    ok
else
    fail "the last pre-existing line was absorbed by the appended block"
fi
cleanup

echo "==> a pre-authenticated mount is completed, not duplicated"
new_install
enable_pd
# Everything already in place, as after a restart with the conf dir mounted
"${INSTALL}/bin/enable-auth.sh"
: > "${INSTALL}/calls.log"
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_not_ran "enable-auth.sh"
assert_auth_fully_enabled
cleanup

echo "==> a complete read-only auth mount is not rewritten"
new_install
"${INSTALL}/bin/enable-auth.sh"
echo "auth.admin_pa=s3cret" >> "${INSTALL}/conf/rest-server.properties"
: > "${INSTALL}/calls.log"
touch -t 202001010000 "${INSTALL}/conf/rest-server.properties"
before_mtime=$(file_mtime "${INSTALL}/conf/rest-server.properties")
chmod 444 "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD
after_mtime=$(file_mtime "${INSTALL}/conf/rest-server.properties")
after_mode=$(file_mode "${INSTALL}/conf/rest-server.properties")
assert_ran "init-store.sh:args=--use-configured-admin-password"
assert_ran "start-hugegraph.sh"
assert_auth_fully_enabled
if [[ "${after_mtime}" == "${before_mtime}" ]]; then
    ok
else
    fail "read-only rest-server.properties was rewritten"
fi
if [[ "${after_mode}" == "444" ]]; then
    ok
else
    fail "read-only rest-server.properties mode became ${after_mode}"
fi
cleanup

echo "==> an incomplete read-only auth mount fails with the missing property"
new_install
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
chmod 444 "${INSTALL}/conf/rest-server.properties"
run_entrypoint_fails -u PASSWORD
assert_not_ran "init-store.sh"
assert_not_ran "start-hugegraph.sh"
assert_output_contains \
    "ERROR: cannot write auth.graph_store to ./conf/rest-server.properties"
cleanup

echo "==> a read-only mount rejects an init-store env override"
new_install
chmod 444 "${INSTALL}/conf/rest-server.properties"
run_entrypoint_fails -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_not_ran "init-store.sh"
assert_not_ran "start-hugegraph.sh"
assert_output_contains \
    "ERROR: cannot write init_store.enabled to ./conf/rest-server.properties"
cleanup

echo "==> a custom authenticator is not held to the usePD requirement"
new_install
echo "auth.authenticator=org.example.auth.LdapAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "start-hugegraph.sh"
assert_ran "init-store.sh"
assert_auth_fully_enabled
assert_prop "auth.authenticator" "org.example.auth.LdapAuthenticator"
cleanup

echo "==> PASSWORD does not turn a custom authenticator into built-in auth"
new_install
echo "auth.authenticator=org.example.auth.LdapAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_ran "start-hugegraph.sh"
assert_ran "init-store.sh"
assert_auth_fully_enabled
assert_prop "auth.authenticator" "org.example.auth.LdapAuthenticator"
assert_no_prop_key "auth.admin_pa"
assert_output_contains "PASSWORD ignored"
cleanup

echo "==> surrounding whitespace is trimmed, inner whitespace is kept"
new_install
printf 'auth.authenticator = %s   \n' \
    "org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
# The Java validator is the source of truth for the class decision; the
# entrypoint must preserve its failure status for this padded value.
run_entrypoint_fails -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false \
    INIT_STORE_STUB_RC=1
assert_not_ran "init-store.sh"
assert_not_ran "start-hugegraph.sh"
cleanup

echo "==> escaped authenticator key and value use Java properties grammar"
new_install
enable_pd
echo 'auth\.authenticator=org.apache.hugegraph.auth.Standard\u0041uthenticator' \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint PASSWORD=s3cret HG_SERVER_INIT_STORE_ENABLED=false
assert_not_ran "enable-auth.sh"
assert_ran "init-store.sh"
assert_ran "start-hugegraph.sh"
assert_auth_fully_enabled
assert_prop_round_trip "auth.admin_pa" "s3cret"
cleanup

echo "==> a value containing spaces survives the round trip"
new_install
enable_pd
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false 'PASSWORD=two words'
assert_prop_round_trip "auth.admin_pa" "two words"
assert_prop_defined_once "auth.admin_pa"
cleanup

echo "==> remote auth is exempt from the usePD requirement"
new_install
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
echo "auth.remote_url=127.0.0.1:8899" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "start-hugegraph.sh"
assert_ran "init-store.sh"
assert_auth_fully_enabled
cleanup

echo "==> PASSWORD is not persisted for remote auth"
new_install
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
echo "auth.remote_url=127.0.0.1:8899" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint PASSWORD=s3cret HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "start-hugegraph.sh"
assert_no_prop_key "auth.admin_pa"
assert_output_contains "PASSWORD ignored"
cleanup

echo "==> configured built-in auth cannot bootstrap with the default password"
new_install
mkdir -p "${INSTALL}/docker"
touch "${INSTALL}/docker/init_complete"
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint_fails -u PASSWORD
assert_not_ran "init-store.sh"
assert_not_ran "start-hugegraph.sh"
assert_output_contains "requires PASSWORD or an explicitly configured non-empty auth.admin_pa"
cleanup

echo "==> skipped built-in auth also rejects the default password"
new_install
enable_pd
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint_fails -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_not_ran "init-store.sh"
assert_not_ran "start-hugegraph.sh"
assert_output_contains "requires PASSWORD or an explicitly configured non-empty auth.admin_pa"
cleanup

echo "==> secret write preserves the inode and restricts the shipped mode"
new_install
enable_pd
before_inode=$(ls -i "${INSTALL}/conf/rest-server.properties" | awk '{print $1}')
before_mode=$(file_mode "${INSTALL}/conf/rest-server.properties")
run_entrypoint PASSWORD=s3cret HG_SERVER_INIT_STORE_ENABLED=false
after_inode=$(ls -i "${INSTALL}/conf/rest-server.properties" | awk '{print $1}')
after_mode=$(file_mode "${INSTALL}/conf/rest-server.properties")
# Rewriting in place matters: a single-file bind mount cannot be replaced by
# rename. The shipped 0644 mode must be restricted before the secret is written.
if [[ "${before_mode}" == "644" ]]; then ok; else fail "fixture mode was ${before_mode}"; fi
if [[ "${before_inode}" == "${after_inode}" ]]; then ok; else fail "inode changed"; fi
if [[ "${after_mode}" == "600" ]]; then ok; else fail "mode became ${after_mode}, expected 600"; fi
assert_prop "init_store.enabled" "false"
assert_prop_round_trip "auth.admin_pa" "s3cret"
cleanup

echo "==> no scratch file is left behind"
new_install
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
if compgen -G "${INSTALL}/conf/rest-server.properties.tmp*" >/dev/null; then
    fail "a set_prop scratch file was left behind"
else
    ok
fi
cleanup

echo "==> the entrypoint's auth block has not drifted from enable-auth.sh"
# The entrypoint reapplies what bin/enable-auth.sh would have done when it
# cannot run the script itself, so both sides must retain every exact invariant.
ENABLE_AUTH="${SELF_DIR}/../../src/assembly/static/bin/enable-auth.sh"
if [[ -f "${ENABLE_AUTH}" ]]; then
    for token in "authentication: {" \
                 "org.apache.hugegraph.auth.StandardAuthenticator" \
                 "WsAndHttpBasicAuthHandler" \
                 "tokens: conf/rest-server.properties" "auth.graph_store" \
                 "HugeFactoryAuthProxy"; do
        if grep -qF "${token}" "${ENABLE_AUTH}"; then
            ok
        else
            fail "enable-auth.sh is missing '${token}'"
        fi
        if grep -qF "${token}" "${ENTRYPOINT}"; then
            ok
        else
            fail "docker-entrypoint.sh is missing '${token}'"
        fi
    done
else
    fail "enable-auth.sh not found at ${ENABLE_AUTH}"
fi

echo "==> the production init-store wrapper preserves Java failures"
new_install
INIT_STORE_WRAPPER="${SELF_DIR}/../../src/assembly/static/bin/init-store.sh"
cp "${INIT_STORE_WRAPPER}" "${INSTALL}/bin/init-store.sh"
mkdir -p "${INSTALL}/lib" "${INSTALL}/plugins"
cat > "${INSTALL}/bin/util.sh" <<'EOF'
configure_riscv64_libatomic() { return 0; }
ensure_path_writable() { return 0; }
EOF
mkdir -p "${INSTALL}/fake-java/bin"
cat > "${INSTALL}/fake-java/bin/java" <<'EOF'
#!/bin/bash
if [[ -n "${FAKE_JAVA_ARGS_FILE:-}" ]]; then
    printf '%s\n' "$*" > "${FAKE_JAVA_ARGS_FILE}"
fi
exit "${FAKE_JAVA_STATUS:-0}"
EOF
chmod +x "${INSTALL}/fake-java/bin/java"
if ( cd "${INSTALL}" && env FAKE_JAVA_STATUS=23 \
        FAKE_JAVA_ARGS_FILE="${INSTALL}/fake-java-args" \
        JAVA_HOME="${INSTALL}/fake-java" ./bin/init-store.sh \
        --use-configured-admin-password ) \
        > "${INSTALL}/init-store-wrapper.out" 2>&1; then
    fail "production init-store.sh hid the Java failure"
else
    status=$?
    if [[ ${status} -eq 23 ]]; then
        ok
    else
        fail "production init-store.sh returned ${status}, expected 23"
    fi
fi
if grep -qF "Initialization finished." \
        "${INSTALL}/init-store-wrapper.out"; then
    fail "production init-store.sh printed success after Java failed"
else
    ok
fi
if grep -qE 'rest-server\.properties --use-configured-admin-password$' \
        "${INSTALL}/fake-java-args"; then
    ok
else
    fail "production init-store.sh did not forward its password-mode flag"
fi
cleanup

echo
echo "passed: ${PASS}, failed: ${FAIL}"
[[ ${FAIL} -eq 0 ]]
