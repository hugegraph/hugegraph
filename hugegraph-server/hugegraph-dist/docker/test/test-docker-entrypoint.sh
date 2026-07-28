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

# A scalar option must end up defined exactly once, on any separator, or the
# properties parser exposes it as a list and a scalar read of it fails
assert_prop_defined_once() {
    local n
    n=$(grep -cE "^[[:space:]]*$1([[:space:]]*[=:]|[[:space:]])" \
        "${INSTALL}/conf/rest-server.properties" 2>/dev/null || true)
    if [[ "${n}" == "1" ]]; then ok; else fail "expected '$1' defined once, found ${n}"; fi
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

# Auth plus skipped init-store is only supported alongside the PD metadata
# path, which is what actually creates the admin account in that mode
enable_pd() {
    echo "usePD=true" >> "${INSTALL}/conf/rest-server.properties"
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
enable_pd
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=false PASSWORD=s3cret
assert_ran "enable-auth.sh"
assert_not_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip via mounted property only: env var absent behaves the same"
new_install
enable_pd
echo "init_store.enabled=false" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret
assert_not_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop "auth.admin_pa" "s3cret"
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
assert_not_ran "init-store.sh"
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

echo "==> uppercase FALSE is honoured, matching the server's boolean parsing"
new_install
enable_pd
run_entrypoint HG_SERVER_INIT_STORE_ENABLED=FALSE PASSWORD=s3cret
assert_not_ran "init-store.sh"
assert_not_ran "init-store.sh:stdin=s3cret"
assert_prop "init_store.enabled" "false"
assert_prop "auth.admin_pa" "s3cret"
assert_no_file "docker/init_complete"
cleanup

echo "==> 'off' and 'no' are honoured too"
for value in off no; do
    new_install
    run_entrypoint -u PASSWORD "HG_SERVER_INIT_STORE_ENABLED=${value}"
    assert_not_ran "init-store.sh"
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
assert_not_ran "init-store.sh"
assert_no_file "docker/init_complete"
cleanup

echo "==> password with backslashes survives the properties round trip"
new_install
enable_pd
run_entrypoint 'PASSWORD=abc\def' HG_SERVER_INIT_STORE_ENABLED=false
assert_not_ran "init-store.sh"
# Written escaped, so the properties parser reads back the original `abc\def`
assert_prop "auth.admin_pa" 'abc\\def'
cleanup

echo "==> skip + PASSWORD without usePD is refused, not silently started"
new_install
if ( cd "${INSTALL}" && env -u HG_SERVER_INIT_STORE_ENABLED PASSWORD=s3cret \
        HG_SERVER_INIT_STORE_ENABLED=false bash "${ENTRYPOINT}" ) >/dev/null 2>&1; then
    fail "expected auth + skip without usePD to be refused"
else
    ok
fi
# Refused before starting the server, and without leaving auth half-enabled
assert_not_ran "start-hugegraph.sh"
assert_not_ran "init-store.sh"
assert_no_file "docker/init_complete"
cleanup

echo "==> skip with auth already in a mounted config, no usePD, is refused too"
new_install
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
if ( cd "${INSTALL}" && env -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false \
        bash "${ENTRYPOINT}" ) >/dev/null 2>&1; then
    fail "expected mounted auth + skip without usePD to be refused"
else
    ok
fi
assert_not_ran "start-hugegraph.sh"
cleanup

echo "==> skip without auth is unaffected by the usePD requirement"
new_install
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "start-hugegraph.sh"
assert_not_ran "init-store.sh"
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
assert_prop "auth.admin_pa" "s3cret"
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
assert_prop_defined_once "auth.authenticator"
assert_prop_defined_once "auth.graph_store"
assert_prop_defined_once "auth.admin_pa"
assert_prop "auth.admin_pa" "s3cret"
cleanup

echo "==> remote auth is exempt from the usePD requirement"
new_install
echo "auth.authenticator=org.apache.hugegraph.auth.StandardAuthenticator" \
    >> "${INSTALL}/conf/rest-server.properties"
echo "auth.remote_url=127.0.0.1:8899" >> "${INSTALL}/conf/rest-server.properties"
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
assert_ran "start-hugegraph.sh"
assert_not_ran "init-store.sh"
cleanup

echo "==> set_prop preserves the config file's inode and mode"
new_install
chmod 600 "${INSTALL}/conf/rest-server.properties"
before_inode=$(ls -i "${INSTALL}/conf/rest-server.properties" | awk '{print $1}')
run_entrypoint -u PASSWORD HG_SERVER_INIT_STORE_ENABLED=false
after_inode=$(ls -i "${INSTALL}/conf/rest-server.properties" | awk '{print $1}')
after_mode=$(stat -c '%a' "${INSTALL}/conf/rest-server.properties" 2>/dev/null \
             || stat -f '%Lp' "${INSTALL}/conf/rest-server.properties")
# Rewriting in place matters: a single-file bind mount cannot be replaced by
# rename, and a rename would drop the mode protecting auth.admin_pa
if [[ "${before_inode}" == "${after_inode}" ]]; then ok; else fail "inode changed"; fi
if [[ "${after_mode}" == "600" ]]; then ok; else fail "mode became ${after_mode}, expected 600"; fi
assert_prop "init_store.enabled" "false"
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

echo
echo "passed: ${PASS}, failed: ${FAIL}"
[[ ${FAIL} -eq 0 ]]
