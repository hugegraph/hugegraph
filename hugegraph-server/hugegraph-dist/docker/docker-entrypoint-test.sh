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
cp "${SCRIPT_DIR}/docker-entrypoint.sh" "${TEST_HOME}/docker-entrypoint.sh"
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
exit 0
EOF
cat > "${TEST_HOME}/bin/init-store.sh" <<'EOF'
#!/usr/bin/env bash
printf 'called\n' >> ./docker/init-store-calls
EOF
cat > "${TEST_HOME}/bin/wait-partition.sh" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${TEST_HOME}/bin/"*.sh

(
    cd "${TEST_HOME}"
    HG_SERVER_BACKEND=hstore \
    HG_SERVER_PD_PEERS=pd:8686 \
    HG_SERVER_USE_PD=true \
    HG_SERVER_REST_URL=http://server:8080 \
    HG_SERVER_MIN_FREE_MEMORY=0 \
    HG_SERVER_AUTH_TOKEN_SECRET=hugegraph-local-jwt-secret-change-me \
        bash ./docker-entrypoint.sh
)
[[ "$(wc -l < "${TEST_HOME}/docker/init-store-calls")" -eq 1 ]]

grep -qx 'backend=hstore' "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -qx 'pd.peers=pd:8686' "${TEST_HOME}/conf/graphs/hugegraph.properties"
grep -qx 'usePD=true' "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'pd.peers=pd:8686' "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'restserver.url=http://server:8080' \
    "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'restserver.min_free_memory=0' \
    "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'auth.token_secret=hugegraph-local-jwt-secret-change-me' \
    "${TEST_HOME}/conf/rest-server.properties"
grep -qx 'auth.token_secret=hugegraph-local-jwt-secret-change-me' \
    "${TEST_HOME}/conf/graphs/hugegraph.properties"

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
(
    cd "${TEST_HOME}"
    PASSWORD=pa bash ./docker-entrypoint.sh
)
reused_secret=$(sed -n 's/^auth\.token_secret=//p' \
    "${TEST_HOME}/conf/rest-server.properties")
[[ "${reused_secret}" == "${rest_secret}" ]]
[[ "$(wc -l < "${TEST_HOME}/docker/init-store-calls")" -eq 3 ]]

echo "PASS: Docker entrypoint configures HStore discovery and authentication"
