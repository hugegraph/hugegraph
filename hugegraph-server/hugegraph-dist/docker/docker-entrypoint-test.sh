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
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_HOME=$(mktemp -d "${TMPDIR:-/tmp}/hugegraph-entrypoint-test.XXXXXX")
trap 'rm -rf "${TEST_HOME}"' EXIT

mkdir -p "${TEST_HOME}/bin" "${TEST_HOME}/conf/graphs" "${TEST_HOME}/docker"
mkdir -p "${TEST_HOME}/rocksdb-data"
cp "${SCRIPT_DIR}/docker-entrypoint.sh" "${TEST_HOME}/docker-entrypoint.sh"
cp "${SCRIPT_DIR}/../src/assembly/static/bin/verify-rocksdb-provider.sh" \
    "${TEST_HOME}/bin/verify-rocksdb-provider.sh"
touch "${TEST_HOME}/docker/init_complete"

cat > "${TEST_HOME}/conf/rest-server.properties" <<'EOF'
restserver.url=http://127.0.0.1:8080
# usePD=true
EOF
cat > "${TEST_HOME}/conf/graphs/hugegraph.properties" <<'EOF'
backend=rocksdb
#pd.peers=127.0.0.1:8686
EOF
cat > "${TEST_HOME}/bin/start-hugegraph.sh" <<'EOF'
#!/usr/bin/env bash
if [[ "${START_TEST_CHILD:-false}" == "true" ]]; then
    sleep 300 &
    printf '%s\n' "$!" > ./bin/pid
fi
exit 0
EOF
cat > "${TEST_HOME}/bin/init-store.sh" <<'EOF'
#!/usr/bin/env bash
printf 'called\n' >> ./docker/init-store-calls
if IFS= read -r password; then
    printf '%s' "${password}" > ./docker/init-store-password
fi
EOF
cat > "${TEST_HOME}/bin/enable-auth.sh" <<'EOF'
#!/usr/bin/env bash
printf 'called\n' >> ./docker/enable-auth-calls
EOF
cat > "${TEST_HOME}/bin/wait-partition.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
cat > "${TEST_HOME}/bin/wait-storage.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${TEST_HOME}/bin/"*.sh

(
    cd "${TEST_HOME}"
    HG_SERVER_BACKEND=hstore \
    HG_SERVER_ROCKSDB_PROVIDER=rocksdb \
    HG_SERVER_PD_PEERS=pd:8686 \
    HG_SERVER_CLUSTER=hg \
    HG_SERVER_USE_PD=true \
    HG_SERVER_REST_URL=http://server:8080 \
    HG_SERVER_MIN_FREE_MEMORY=0 \
    HG_SERVER_AUTH_TOKEN_SECRET=12345678901234567890123456789012 \
        bash ./docker-entrypoint.sh
)
[[ "$(wc -l < "${TEST_HOME}/docker/init-store-calls")" -eq 1 ]]

grep -qx 'backend=hstore' "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -qx 'rocksdb.provider=rocksdb' \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -qx 'pd.peers=pd:8686' "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -qx 'usePD=true' "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'pd.peers=pd:8686' "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'cluster=hg' "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'restserver.url=http://server:8080' \
    "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'restserver.min_free_memory=0' \
    "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'auth.token_secret=12345678901234567890123456789012' \
    "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'auth.token_secret=12345678901234567890123456789012' \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"

cp "${TEST_HOME}/conf/graphs/hugegraph.properties" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties.before-hstore-topling"
if (
    cd "${TEST_HOME}"
    HG_SERVER_BACKEND=hstore \
    HG_SERVER_ROCKSDB_PROVIDER=topling \
        bash ./docker-entrypoint.sh
); then
    echo "HStore Server unexpectedly selected a local Topling provider" >&2
    exit 1
fi
cmp "${TEST_HOME}/conf/graphs/hugegraph.properties.before-hstore-topling" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"

mkdir -p "${TEST_HOME}/lib/topling" "${TEST_HOME}/library" \
         "${TEST_HOME}/topling-data"
touch "${TEST_HOME}/lib/topling/rocksdbjni-topling.jar"
touch "${TEST_HOME}/library/librocksdbjni-linux64.so"
if (
    cd "${TEST_HOME}"
    HG_SERVER_BACKEND=hstore \
    HG_SERVER_ROCKSDB_PROVIDER=rocksdb \
        bash ./docker-entrypoint.sh
); then
    echo "HStore Server unexpectedly accepted a local Topling payload" >&2
    exit 1
fi
rm -f "${TEST_HOME}/lib/topling/rocksdbjni-topling.jar"
rm -f "${TEST_HOME}/library/librocksdbjni-linux64.so"

cp "${TEST_HOME}/conf/graphs/hugegraph.properties" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties.before-invalid-provider"
if (
    cd "${TEST_HOME}"
    HG_SERVER_ROCKSDB_PROVIDER=invalid bash ./docker-entrypoint.sh
); then
    echo "invalid RocksDB provider unexpectedly succeeded" >&2
    exit 1
fi
cmp "${TEST_HOME}/conf/graphs/hugegraph.properties.before-invalid-provider" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"

(
    cd "${TEST_HOME}"
    HG_SERVER_BACKEND=rocksdb \
    HG_SERVER_ROCKSDB_PROVIDER=topling \
    HG_SERVER_ENFORCE_PROVIDER_MARKER=true \
        bash ./docker-entrypoint.sh
)
grep -qx "rocksdb.data_path=${TEST_HOME}/topling-data/data" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -qx "rocksdb.wal_path=${TEST_HOME}/topling-data/wal" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -Fqx 'component=server' \
    "${TEST_HOME}/topling-data/.hugegraph-rocksdb-provider"
grep -Fqx 'provider=topling' \
    "${TEST_HOME}/topling-data/.hugegraph-rocksdb-provider"
cp "${TEST_HOME}/conf/graphs/hugegraph.properties" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties.before-provider-mismatch"
if (
    cd "${TEST_HOME}"
    HG_SERVER_BACKEND=rocksdb \
    HG_SERVER_ROCKSDB_PROVIDER=rocksdb \
    HG_SERVER_DATA_PATH="${TEST_HOME}/topling-data" \
        bash ./docker-entrypoint.sh
); then
    echo "provider-mismatched Server data path unexpectedly succeeded" >&2
    exit 1
fi
cmp "${TEST_HOME}/conf/graphs/hugegraph.properties.before-provider-mismatch" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"

cp "${TEST_HOME}/conf/rest-server.properties" \
    "${TEST_HOME}/conf/rest-server.properties.before-short-secret"
cp "${TEST_HOME}/conf/graphs/hugegraph.properties" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties.before-short-secret"
if (
    cd "${TEST_HOME}"
    PASSWORD=pa \
    HG_SERVER_AUTH_TOKEN_SECRET=1234567890123456789012345678901 \
        bash ./docker-entrypoint.sh
); then
    echo "short authentication token secret unexpectedly succeeded" >&2
    exit 1
fi
cmp "${TEST_HOME}/conf/rest-server.properties.before-short-secret" \
    "${TEST_HOME}/conf/rest-server.properties"
cmp "${TEST_HOME}/conf/graphs/hugegraph.properties.before-short-secret" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"
[[ ! -e "${TEST_HOME}/docker/enable-auth-calls" ]]

sed -i '/^auth\.token_secret=/d' "${TEST_HOME}/conf/rest-server.properties"
sed -i '/^auth\.token_secret=/d' "${TEST_HOME}/conf/graphs/hugegraph.properties"
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
rest_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
graph_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/graphs/hugegraph.properties")
[[ ${#rest_secret} -ge 43 ]]
[[ "${rest_secret}" == "${graph_secret}" ]]
grep -qx 'auth.admin_pa=pa' "${TEST_HOME}/conf/rest-server.properties"
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
reused_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
[[ "${reused_secret}" == "${rest_secret}" ]]

sed -i '/^auth\.token_secret=/d' \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"
sed -i "s|^auth\\.token_secret=.*|auth.token_secret:  ${rest_secret}|" \
    "${TEST_HOME}/conf/rest-server.properties"
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
grep -qx "auth.token_secret=${rest_secret}" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"

sed -i '/^auth\.token_secret=/d' \
    "${TEST_HOME}/conf/rest-server.properties"
sed -i "s|^auth\\.token_secret=.*|auth.token_secret  ${rest_secret}|" \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
grep -qx "auth.token_secret=${rest_secret}" \
    "${TEST_HOME}/conf/rest-server.properties"

[[ "$(wc -l < "${TEST_HOME}/docker/init-store-calls")" -eq 6 ]]
[[ "$(wc -l < "${TEST_HOME}/docker/enable-auth-calls")" -eq 4 ]]

(
    cd "${TEST_HOME}"
    PASSWORD=pa \
    HG_SERVER_AUTH_TOKEN_SECRET='Strong\Secret 9!0123456789abcdef' \
        bash ./docker-entrypoint.sh
)
complex_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
reused_complex_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
[[ "${reused_complex_secret}" == "${complex_secret}" ]]
[[ "${reused_complex_secret}" == \
   'Strong\\Secret\ 9!0123456789abcdef' ]]

(
    cd "${TEST_HOME}"
    PASSWORD=pa \
    HG_SERVER_AUTH_TOKEN_SECRET='SecretEnds 0123456789abcdefABCDE ' \
        bash ./docker-entrypoint.sh
)
trailing_space_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
reused_trailing_space_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
[[ "${trailing_space_secret}" == \
   'SecretEnds\ 0123456789abcdefABCDE\ ' ]]
[[ "${reused_trailing_space_secret}" == "${trailing_space_secret}" ]]
grep -Fqx 'auth.admin_pa=pa' \
    "${TEST_HOME}/conf/rest-server.properties"

(
    cd "${TEST_HOME}"
    PASSWORD='Strong\Pass 9!' bash ./docker-entrypoint.sh
)
grep -Fqx 'auth.admin_pa=Strong\\Pass\ 9!' \
    "${TEST_HOME}/conf/rest-server.properties"

rm -f "${TEST_HOME}/docker/init_complete"
rm -f "${TEST_HOME}/rocksdb-data/.hugegraph-state/init_complete"
(
    cd "${TEST_HOME}"
    PASSWORD=-n bash ./docker-entrypoint.sh
)
grep -Fqx -- '-n' "${TEST_HOME}/docker/init-store-password"

rm -f "${TEST_HOME}/bin/pid"
(
    cd "${TEST_HOME}"
    exec setsid env \
        START_TEST_CHILD=true \
        HG_SERVER_BACKEND=hstore \
        HG_SERVER_ROCKSDB_PROVIDER=rocksdb \
        bash ./docker-entrypoint.sh
) &
entrypoint_pid=$!
for _ in $(seq 1 10); do
    [[ -s "${TEST_HOME}/bin/pid" ]] && break
    sleep 1
done
[[ -s "${TEST_HOME}/bin/pid" ]]
child_pid=$(<"${TEST_HOME}/bin/pid")
kill -TERM -- "-${entrypoint_pid}"
for _ in $(seq 1 15); do
    ! kill -0 "${entrypoint_pid}" 2>/dev/null && break
    sleep 1
done
if kill -0 "${entrypoint_pid}" 2>/dev/null; then
    kill -KILL -- "-${entrypoint_pid}" 2>/dev/null || true
    wait "${entrypoint_pid}" 2>/dev/null || true
    echo "Docker entrypoint did not finish SIGTERM handling" >&2
    exit 1
fi
if ! wait "${entrypoint_pid}"; then
    echo "Docker entrypoint did not exit cleanly after SIGTERM" >&2
    exit 1
fi
if kill -0 "${child_pid}" 2>/dev/null; then
    echo "Docker entrypoint left its Server child running" >&2
    exit 1
fi

echo "PASS: Docker entrypoint configures HStore discovery and authentication"
