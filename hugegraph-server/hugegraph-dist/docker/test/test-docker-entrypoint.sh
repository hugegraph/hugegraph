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
# and on the resulting config, without needing a JVM, a backend or Docker.
#
# Usage: hugegraph-server/hugegraph-dist/docker/test/test-docker-entrypoint.sh

set -uo pipefail

SELF_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENTRYPOINT="${SELF_DIR}/../docker-entrypoint.sh"

PASS=0
FAIL=0
SKIP=0

# docker-entrypoint.sh rewrites an existing property with GNU `sed -ri`, which
# BSD sed rejects. The image is Linux, so the cases that exercise that branch
# are skipped rather than failed when running locally on macOS.
if sed --version >/dev/null 2>&1; then
    GNU_SED=1
else
    GNU_SED=0
fi

skip_without_gnu_sed() {
    if [[ ${GNU_SED} -eq 1 ]]; then
        return 1
    fi
    echo "    SKIP: needs GNU sed (set_prop rewrites an existing property)"
    SKIP=$((SKIP + 1))
    return 0
}

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

assert_prop() {
    local expected="$1=$2"
    if grep -qxF "${expected}" "${INSTALL}/conf/rest-server.properties" 2>/dev/null; then
        ok
    else
        fail "expected property '${expected}' in rest-server.properties"
    fi
}

assert_no_prop_key() {
    if grep -qE "^[[:space:]]*$1[[:space:]]*=" \
            "${INSTALL}/conf/rest-server.properties" 2>/dev/null; then
        fail "expected no '$1' property"
    else
        ok
    fi
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
    echo "backend=rocksdb" > "${INSTALL}/conf/graphs/hugegraph.properties"

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
if [[ ! -t 0 ]]; then
    stdin=\$(cat)
    [[ -n "\${stdin}" ]] && echo "init-store.sh:stdin=\${stdin}" >> "${INSTALL}/calls.log"
fi
exit 0
EOF
    chmod +x "${INSTALL}/bin/init-store.sh"

    cat > "${INSTALL}/bin/enable-auth.sh" <<EOF
#!/bin/bash
echo "enable-auth.sh" >> "${INSTALL}/calls.log"
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
EOF
    chmod +x "${INSTALL}/bin/enable-auth.sh"

    : > "${INSTALL}/calls.log"
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

cleanup() { [[ -n "${INSTALL:-}" ]] && rm -rf "${INSTALL}"; }
trap cleanup EXIT

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
cleanup

echo "==> skip via env: init-store never runs and no init flag is written"
new_install
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "wait-storage.sh"
assert_not_ran "init-store.sh"
assert_prop "init_store.enabled" "false"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip + PASSWORD: password reaches auth.admin_pa, not init-store stdin"
new_install
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_ran "enable-auth.sh"
assert_not_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip via mounted property only: env var absent behaves the same"
new_install
echo "init_store.enabled=false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret
assert_not_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> env wins over a conflicting mounted property"
if ! skip_without_gnu_sed; then
    new_install
    echo "init_store.enabled=false" >> "${INSTALL}/conf/rest-server.properties"
    run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=true
    assert_ran "init-store.sh"
    assert_file "docker/init_complete"
    cleanup
fi

echo "==> false then true: a restart with init enabled still initializes"
if ! skip_without_gnu_sed; then
    new_install
    run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
    assert_not_ran "init-store.sh"
    assert_no_file "docker/init_complete"
    : > "${INSTALL}/calls.log"
    run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=true
    assert_ran "init-store.sh"
    assert_file "docker/init_complete"
    cleanup
fi

echo "==> restart with init enabled: the flag file suppresses re-init"
new_install
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_ran "init-store.sh"
: > "${INSTALL}/calls.log"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED -u PASSWORD
assert_not_ran "init-store.sh"
assert_file "docker/init_complete"
cleanup

echo
echo "passed: ${PASS}, failed: ${FAIL}, skipped cases: ${SKIP}"
[[ ${FAIL} -eq 0 ]]
