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
set -eo pipefail

PD_COUNT=${1:-3}
STORE_COUNT=${2:-3}
SERVER_COUNT=${3:-3}

HOME_DIR=$(pwd)

PROPERTIES_FILE="$HOME_DIR/hugegraph-commons/hugegraph-common/src/main/resources/version.properties"
if [ -f "$PROPERTIES_FILE" ]; then
    VERSION=$(grep "^VersionInBash=" "$PROPERTIES_FILE" | cut -d'=' -f2)
    if [ -z "$VERSION" ]; then
        echo "Error: VersionInBash not found in $PROPERTIES_FILE"
        exit 1
    fi
else
    echo "Error: properties file not found at $PROPERTIES_FILE"
    exit 1
fi

PD_DIR="$HOME_DIR/hugegraph-pd/apache-hugegraph-pd-$VERSION"
STORE_DIR="$HOME_DIR/hugegraph-store/apache-hugegraph-store-$VERSION"
SERVER_DIR="$HOME_DIR/hugegraph-server/apache-hugegraph-server-$VERSION"

if [ ! -d "$PD_DIR" ]; then
    echo "Error: PD directory not found at $PD_DIR"
    exit 1
fi
if [ ! -d "$STORE_DIR" ]; then
    echo "Error: Store directory not found at $STORE_DIR"
    exit 1
fi
if [ ! -d "$SERVER_DIR" ]; then
    echo "Error: Server directory not found at $SERVER_DIR"
    exit 1
fi

PD_BASE_GRPC=8686
PD_BASE_REST=8620
PD_BASE_RAFT=8610

STORE_BASE_GRPC=8500
STORE_BASE_REST=8520
STORE_BASE_RAFT=8510

SERVER_BASE_REST=8080
SERVER_BASE_RPC=8091
SERVER_BASE_GREMLIN=8182

IS_CI=false
if [ "${CI:-}" = "true" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    IS_CI=true
fi

if [ "$IS_CI" = "true" ]; then
    PD_JAVA_OPTIONS="-Xms128m -Xmx256m -XX:+UseSerialGC -XX:+HeapDumpOnOutOfMemoryError"
    STORE_JAVA_OPTIONS="-Xms128m -Xmx512m -XX:+UseSerialGC -XX:MetaspaceSize=128M -XX:+HeapDumpOnOutOfMemoryError"
    SERVER_JAVA_OPTIONS="-Xms128m -Xmx512m -XX:+UseSerialGC -XX:+HeapDumpOnOutOfMemoryError"
    PD_WAIT_RETRIES=60
    STORE_WAIT_RETRIES=60
    SERVER_WAIT_RETRIES=90
    STARTUP_RETRY_COUNT=1
else
    PD_JAVA_OPTIONS="-Xms256m -Xmx1024m -XX:+HeapDumpOnOutOfMemoryError"
    STORE_JAVA_OPTIONS="-Xms256m -Xmx1024m -XX:MetaspaceSize=256M -XX:+HeapDumpOnOutOfMemoryError"
    SERVER_JAVA_OPTIONS="-Xms256m -Xmx1024m -XX:+HeapDumpOnOutOfMemoryError"
    PD_WAIT_RETRIES=30
    STORE_WAIT_RETRIES=30
    SERVER_WAIT_RETRIES=60
    STARTUP_RETRY_COUNT=0
fi

function find_free_port() {
    local start_port=$1
    local end_port=$((start_port + 100))
    local port=$start_port
    while [ $port -lt $end_port ]; do
        if ! lsof -i :"$port" >/dev/null 2>&1; then
            echo "$port"
            return 0
        fi
        port=$((port + 1))
    done
    echo ""
    return 1
}

function sed_in_place() {
    local expression=$1
    local file=$2
    case "$(uname)" in
        Darwin) sed -i '' "$expression" "$file" ;;
        *) sed -i "$expression" "$file" ;;
    esac
}

function set_property() {
    local file=$1
    local key=$2
    local value=$3
    if grep -q "^$key=" "$file"; then
        sed_in_place "s|^$key=.*|$key=$value|g" "$file"
    elif grep -q "^#$key=" "$file"; then
        sed_in_place "s|^#$key=.*|$key=$value|g" "$file"
    else
        echo "$key=$value" >> "$file"
    fi
}

function set_yaml_key() {
    local file=$1
    local key=$2
    local value=$3
    if grep -q "^${key}:" "$file"; then
        sed_in_place "s|^${key}:.*|${key}: ${value}|g" "$file"
    elif grep -q "^#${key}:" "$file"; then
        sed_in_place "s|^#${key}:.*|${key}: ${value}|g" "$file"
    else
        sed_in_place "1i|${key}: ${value}|" "$file"
    fi
}

function show_memory() {
    if [ "$IS_CI" = "true" ]; then
        echo "  [Memory] $(free -m 2>/dev/null | head -2 | tail -1 | awk '{printf "used=%sM free=%sM total=%sM", $3, $4, $2}')"
    fi
}

function wait_for_http() {
    local url=$1
    local max_retries=${2:-30}
    local expected_status=${3:-"200"}
    local retry=0
    while [ $retry -lt $max_retries ]; do
        STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null || echo "000")
        if [ "$STATUS" = "$expected_status" ] || ([ "$expected_status" = "200" ] && ([ "$STATUS" = "200" ] || [ "$STATUS" = "401" ])); then
            echo "  -> $url is ready (HTTP $STATUS)"
            return 0
        fi
        retry=$((retry + 1))
        if [ $((retry % 10)) -eq 0 ]; then
            show_memory
        fi
        echo "  -> Waiting for $url... ($retry/$max_retries, HTTP $STATUS)"
        sleep 3
    done
    echo "  -> $url did not become ready in time (last HTTP $STATUS)"
    return 1
}

function start_with_retry() {
    local name=$1
    local start_cmd=$2
    local log_file=$3
    local retry=0
    while [ $retry -le $STARTUP_RETRY_COUNT ]; do
        if eval "$start_cmd"; then
            return 0
        fi
        retry=$((retry + 1))
        if [ $retry -le $STARTUP_RETRY_COUNT ]; then
            echo "  -> Retrying $name startup (attempt $((retry + 1))/$((STARTUP_RETRY_COUNT + 1)))..."
            sleep 5
        fi
    done
    echo "ERROR: $name failed to start after $((STARTUP_RETRY_COUNT + 1)) attempts"
    echo "=== Last 50 lines of $log_file ==="
    tail -50 "$log_file" 2>/dev/null || echo "(log file not found)"
    return 1
}

PD_GRPC_PORTS=()
PD_REST_PORTS=()
PD_RAFT_PORTS=()

for i in $(seq 0 $((PD_COUNT - 1))); do
    GRPC_PORT=$(find_free_port $((PD_BASE_GRPC + i)))
    REST_PORT=$(find_free_port $((PD_BASE_REST + i)))
    RAFT_PORT=$(find_free_port $((PD_BASE_RAFT + i)))
    if [ -z "$GRPC_PORT" ] || [ -z "$REST_PORT" ] || [ -z "$RAFT_PORT" ]; then
        echo "ERROR: Cannot find free ports for PD node ${i}"
        exit 1
    fi
    PD_GRPC_PORTS+=("$GRPC_PORT")
    PD_REST_PORTS+=("$REST_PORT")
    PD_RAFT_PORTS+=("$RAFT_PORT")
done

PD_RAFT_PEERS=""
PD_GRPC_LIST=""
for i in $(seq 0 $((PD_COUNT - 1))); do
    if [ -n "$PD_RAFT_PEERS" ]; then
        PD_RAFT_PEERS="${PD_RAFT_PEERS},"
    fi
    PD_RAFT_PEERS="${PD_RAFT_PEERS}127.0.0.1:${PD_RAFT_PORTS[$i]}"
    if [ -n "$PD_GRPC_LIST" ]; then
        PD_GRPC_LIST="${PD_GRPC_LIST},"
    fi
    PD_GRPC_LIST="${PD_GRPC_LIST}127.0.0.1:${PD_GRPC_PORTS[$i]}"
done

STORE_GRPC_PORTS=()
STORE_REST_PORTS=()
STORE_RAFT_PORTS=()

for i in $(seq 0 $((STORE_COUNT - 1))); do
    GRPC_PORT=$(find_free_port $((STORE_BASE_GRPC + i)))
    REST_PORT=$(find_free_port $((STORE_BASE_REST + i)))
    RAFT_PORT=$(find_free_port $((STORE_BASE_RAFT + i)))
    if [ -z "$GRPC_PORT" ] || [ -z "$REST_PORT" ] || [ -z "$RAFT_PORT" ]; then
        echo "ERROR: Cannot find free ports for Store node ${i}"
        exit 1
    fi
    STORE_GRPC_PORTS+=("$GRPC_PORT")
    STORE_REST_PORTS+=("$REST_PORT")
    STORE_RAFT_PORTS+=("$RAFT_PORT")
done

STORE_GRPC_LIST=""
for i in $(seq 0 $((STORE_COUNT - 1))); do
    if [ -n "$STORE_GRPC_LIST" ]; then
        STORE_GRPC_LIST="${STORE_GRPC_LIST},"
    fi
    STORE_GRPC_LIST="${STORE_GRPC_LIST}127.0.0.1:${STORE_GRPC_PORTS[$i]}"
done

SERVER_REST_PORTS=()
SERVER_RPC_PORTS=()
SERVER_GREMLIN_PORTS=()

for i in $(seq 0 $((SERVER_COUNT - 1))); do
    REST_PORT=$(find_free_port $((SERVER_BASE_REST + i)))
    RPC_PORT=$(find_free_port $((SERVER_BASE_RPC + i)))
    GREMLIN_PORT=$(find_free_port $((SERVER_BASE_GREMLIN + i)))
    if [ -z "$REST_PORT" ] || [ -z "$RPC_PORT" ] || [ -z "$GREMLIN_PORT" ]; then
        echo "ERROR: Cannot find free ports for Server node ${i}"
        exit 1
    fi
    SERVER_REST_PORTS+=("$REST_PORT")
    SERVER_RPC_PORTS+=("$RPC_PORT")
    SERVER_GREMLIN_PORTS+=("$GREMLIN_PORT")
done

echo "=== Environment ==="
echo "  CI: $IS_CI"
echo "  Cluster: ${PD_COUNT} PD + ${STORE_COUNT} Store + ${SERVER_COUNT} Server"
echo "  PD JVM: $PD_JAVA_OPTIONS"
echo "  Store JVM: $STORE_JAVA_OPTIONS"
echo "  Server JVM: $SERVER_JAVA_OPTIONS"
echo "  Version: $VERSION"
show_memory

echo ""
echo "=== Port allocation ==="
PORT_INFO_FILE="$HOME_DIR/cluster-ports.txt"
> "$PORT_INFO_FILE"
for i in $(seq 0 $((PD_COUNT - 1))); do
    echo "  PD node ${i}: grpc=${PD_GRPC_PORTS[$i]}, rest=${PD_REST_PORTS[$i]}, raft=${PD_RAFT_PORTS[$i]}"
    echo "PD_REST_${i}=${PD_REST_PORTS[$i]}" >> "$PORT_INFO_FILE"
done
for i in $(seq 0 $((STORE_COUNT - 1))); do
    echo "  Store node ${i}: grpc=${STORE_GRPC_PORTS[$i]}, rest=${STORE_REST_PORTS[$i]}, raft=${STORE_RAFT_PORTS[$i]}"
    echo "STORE_REST_${i}=${STORE_REST_PORTS[$i]}" >> "$PORT_INFO_FILE"
done
for i in $(seq 0 $((SERVER_COUNT - 1))); do
    echo "  Server node ${i}: rest=${SERVER_REST_PORTS[$i]}, rpc=${SERVER_RPC_PORTS[$i]}, gremlin=${SERVER_GREMLIN_PORTS[$i]}"
    echo "SERVER_REST_${i}=${SERVER_REST_PORTS[$i]}" >> "$PORT_INFO_FILE"
done
echo "  Port info saved to $PORT_INFO_FILE"

echo ""
echo "=== Starting ${PD_COUNT} PD nodes ==="
for i in $(seq 0 $((PD_COUNT - 1))); do
    GRPC_PORT=${PD_GRPC_PORTS[$i]}
    REST_PORT=${PD_REST_PORTS[$i]}
    RAFT_PORT=${PD_RAFT_PORTS[$i]}
    DATA_PATH="./pd_data_${i}"

    INSTANCE_DIR="${PD_DIR}_${i}"
    rm -rf "$INSTANCE_DIR"
    cp -r "$PD_DIR" "$INSTANCE_DIR"

    export SPRING_APPLICATION_JSON="{\"grpc\":{\"host\":\"127.0.0.1\",\"port\":\"${GRPC_PORT}\"},\"server\":{\"port\":\"${REST_PORT}\"},\"raft\":{\"address\":\"127.0.0.1:${RAFT_PORT}\",\"peers-list\":\"${PD_RAFT_PEERS}\"},\"pd\":{\"data-path\":\"${DATA_PATH}\",\"initial-store-list\":\"${STORE_GRPC_LIST}\",\"initial-store-count\":${STORE_COUNT}}}"

    echo "Starting PD node ${i}: grpc=${GRPC_PORT}, rest=${REST_PORT}, raft=${RAFT_PORT}"
    pushd "$INSTANCE_DIR" > /dev/null
    export JAVA_OPTIONS="$PD_JAVA_OPTIONS"
    start_with_retry "PD node ${i}" "bash bin/start-hugegraph-pd.sh" "logs/hugegraph-pd.log" || exit 1
    popd > /dev/null
    unset SPRING_APPLICATION_JSON
    unset JAVA_OPTIONS
done

echo ""
echo "=== Waiting for PD cluster to be ready ==="
PD_READY=true
for i in $(seq 0 $((PD_COUNT - 1))); do
    REST_PORT=${PD_REST_PORTS[$i]}
    if ! wait_for_http "http://127.0.0.1:${REST_PORT}/actuator/health" $PD_WAIT_RETRIES; then
        echo "ERROR: PD node ${i} on port ${REST_PORT} is not ready"
        PD_READY=false
    fi
done
if [ "$PD_READY" = "false" ]; then
    echo "ERROR: PD cluster is not ready, aborting"
    for i in $(seq 0 $((PD_COUNT - 1))); do
        INSTANCE_DIR="${PD_DIR}_${i}"
        if [ -d "$INSTANCE_DIR" ]; then
            echo "--- PD node ${i} log ---"
            tail -30 "$INSTANCE_DIR/logs/hugegraph-pd.log" 2>/dev/null || true
        fi
    done
    exit 1
fi

echo ""
echo "=== Starting ${STORE_COUNT} Store nodes ==="
for i in $(seq 0 $((STORE_COUNT - 1))); do
    GRPC_PORT=${STORE_GRPC_PORTS[$i]}
    REST_PORT=${STORE_REST_PORTS[$i]}
    RAFT_PORT=${STORE_RAFT_PORTS[$i]}
    DATA_PATH="./storage_${i}"

    INSTANCE_DIR="${STORE_DIR}_${i}"
    rm -rf "$INSTANCE_DIR"
    cp -r "$STORE_DIR" "$INSTANCE_DIR"

    export SPRING_APPLICATION_JSON="{\"pdserver\":{\"address\":\"${PD_GRPC_LIST}\"},\"grpc\":{\"host\":\"127.0.0.1\",\"port\":\"${GRPC_PORT}\"},\"raft\":{\"address\":\"127.0.0.1:${RAFT_PORT}\"},\"server\":{\"port\":\"${REST_PORT}\"},\"app\":{\"data-path\":\"${DATA_PATH}\"}}"

    echo "Starting Store node ${i}: grpc=${GRPC_PORT}, rest=${REST_PORT}, raft=${RAFT_PORT}"
    pushd "$INSTANCE_DIR" > /dev/null
    export JAVA_OPTIONS="$STORE_JAVA_OPTIONS"
    start_with_retry "Store node ${i}" "bash bin/start-hugegraph-store.sh" "logs/hugegraph-store.log" || exit 1
    popd > /dev/null
    unset SPRING_APPLICATION_JSON
    unset JAVA_OPTIONS
done

echo ""
echo "=== Waiting for Store cluster to be ready ==="
STORE_READY=true
for i in $(seq 0 $((STORE_COUNT - 1))); do
    REST_PORT=${STORE_REST_PORTS[$i]}
    if ! wait_for_http "http://127.0.0.1:${REST_PORT}/actuator/health" $STORE_WAIT_RETRIES; then
        echo "ERROR: Store node ${i} on port ${REST_PORT} is not ready"
        STORE_READY=false
    fi
done
if [ "$STORE_READY" = "false" ]; then
    echo "ERROR: Store cluster is not ready, aborting"
    for i in $(seq 0 $((STORE_COUNT - 1))); do
        INSTANCE_DIR="${STORE_DIR}_${i}"
        if [ -d "$INSTANCE_DIR" ]; then
            echo "--- Store node ${i} log ---"
            tail -30 "$INSTANCE_DIR/logs/hugegraph-store.log" 2>/dev/null || true
        fi
    done
    exit 1
fi

echo ""
echo "=== Starting ${SERVER_COUNT} Server nodes ==="
for i in $(seq 0 $((SERVER_COUNT - 1))); do
    REST_PORT=${SERVER_REST_PORTS[$i]}
    RPC_PORT=${SERVER_RPC_PORTS[$i]}
    GREMLIN_PORT=${SERVER_GREMLIN_PORTS[$i]}

    INSTANCE_DIR="${SERVER_DIR}_${i}"
    rm -rf "$INSTANCE_DIR"
    cp -r "$SERVER_DIR" "$INSTANCE_DIR"

    CONF="${INSTANCE_DIR}/conf/graphs/hugegraph.properties"
    REST_CONF="${INSTANCE_DIR}/conf/rest-server.properties"
    GREMLIN_CONF="${INSTANCE_DIR}/conf/gremlin-server.yaml"

    sed_in_place "s|backend=.*|backend=hstore|g" "$CONF"
    sed_in_place "s|serializer=.*|serializer=binary|g" "$CONF"

    if grep -q "pd.peers" "$CONF"; then
        sed_in_place "s|pd.peers=.*|pd.peers=${PD_GRPC_LIST}|g" "$CONF"
    else
        echo "pd.peers=${PD_GRPC_LIST}" >> "$CONF"
    fi

    set_property "$REST_CONF" "restserver.url" "http://127.0.0.1:${REST_PORT}"
    set_property "$REST_CONF" "rpc.server_port" "${RPC_PORT}"
    set_property "$REST_CONF" "gremlinserver.url" "127.0.0.1:${GREMLIN_PORT}"

    set_yaml_key "$GREMLIN_CONF" "host" "127.0.0.1"
    set_yaml_key "$GREMLIN_CONF" "port" "${GREMLIN_PORT}"

    if grep -q "usePD" "$REST_CONF"; then
        sed_in_place "s|usePD=.*|usePD=true|g" "$REST_CONF"
    else
        echo "usePD=true" >> "$REST_CONF"
    fi

    if grep -q "server.id" "$REST_CONF"; then
        sed_in_place "s|server.id=.*|server.id=server${i}|g" "$REST_CONF"
    else
        echo "server.id=server${i}" >> "$REST_CONF"
    fi

    if [ "$i" -eq 0 ]; then
        ROLE="master"
    else
        ROLE="worker"
    fi

    if grep -q "server.role" "$REST_CONF"; then
        sed_in_place "s|server.role=.*|server.role=${ROLE}|g" "$REST_CONF"
    else
        echo "server.role=${ROLE}" >> "$REST_CONF"
    fi

    echo "Starting Server node ${i}: rest=${REST_PORT}, rpc=${RPC_PORT}, gremlin=${GREMLIN_PORT}, role=${ROLE}"
    pushd "$INSTANCE_DIR" > /dev/null
    export JAVA_OPTIONS="$SERVER_JAVA_OPTIONS"
    printf 'pa\n' | bash bin/init-store.sh 2>/dev/null || true
    start_with_retry "Server node ${i}" "bash bin/start-hugegraph.sh -t 240" "logs/hugegraph-server.log" || exit 1
    popd > /dev/null
    unset JAVA_OPTIONS
done

echo ""
echo "=== Waiting for Server cluster to be ready ==="
SERVER_READY=true
for i in $(seq 0 $((SERVER_COUNT - 1))); do
    REST_PORT=${SERVER_REST_PORTS[$i]}
    if ! wait_for_http "http://127.0.0.1:${REST_PORT}/graphs" $SERVER_WAIT_RETRIES; then
        echo "ERROR: Server node ${i} on port ${REST_PORT} is not ready"
        SERVER_READY=false
    fi
done
if [ "$SERVER_READY" = "false" ]; then
    echo "ERROR: Server cluster is not ready, aborting"
    for i in $(seq 0 $((SERVER_COUNT - 1))); do
        INSTANCE_DIR="${SERVER_DIR}_${i}"
        if [ -d "$INSTANCE_DIR" ]; then
            echo "--- Server node ${i} log ---"
            tail -30 "$INSTANCE_DIR/logs/hugegraph-server.log" 2>/dev/null || true
        fi
    done
    exit 1
fi

echo ""
echo "=== Cluster started: ${PD_COUNT} PD + ${STORE_COUNT} Store + ${SERVER_COUNT} Server ==="
show_memory
