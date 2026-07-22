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

if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "USAGE: $0 IMAGE [ARCH]" >&2
    exit 1
fi

IMAGE=$1
EXPECTED_ARCH=${2:-riscv64}
TRAVIS_DIR=$(cd "$(dirname "$0")" && pwd)
CONTAINER_NAME="hugegraph-runtime-smoke-${EXPECTED_ARCH}-$$"
CONTAINER_STARTED=false
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hugegraph-docker-runtime-smoke.XXXXXX")
CONTAINER_LOG="$WORK_DIR/container.log"
RUN_ID="$(date +%s)_$$"

case "$EXPECTED_ARCH" in
    amd64)
        EXPECTED_UNAME_ARCH=x86_64
        SERVER_STARTUP_TIMEOUT=300
        CONTAINER_READY_TIMEOUT=300
        ;;
    arm64)
        EXPECTED_UNAME_ARCH=aarch64
        SERVER_STARTUP_TIMEOUT=300
        CONTAINER_READY_TIMEOUT=300
        ;;
    riscv64)
        EXPECTED_UNAME_ARCH=riscv64
        SERVER_STARTUP_TIMEOUT=300
        CONTAINER_READY_TIMEOUT=600
        ;;
    *)
        echo "Unsupported Docker architecture: $EXPECTED_ARCH" >&2
        exit 1
        ;;
esac

cleanup() {
    local status=$?
    trap - EXIT
    if [[ "$CONTAINER_STARTED" == "true" ]]; then
        docker rm --force --volumes "$CONTAINER_NAME" >/dev/null 2>&1 || status=1
    fi
    rm -rf "$WORK_DIR"
    exit "$status"
}
trap cleanup EXIT

for command in docker curl jq; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Required command is unavailable: $command" >&2
        exit 1
    fi
done

IMAGE_ARCH=$(docker image inspect --format '{{.Architecture}}' "$IMAGE")
if [[ "$IMAGE_ARCH" != "$EXPECTED_ARCH" ]]; then
    echo "Expected image architecture $EXPECTED_ARCH, got $IMAGE_ARCH" >&2
    exit 1
fi

docker run --rm --platform "linux/$EXPECTED_ARCH" \
    --env EXPECTED_ARCH="$EXPECTED_UNAME_ARCH" \
    --env EXPECTED_JAVA_MAJOR=11 \
    --volume "$TRAVIS_DIR:/smoke:ro" \
    --entrypoint /bin/bash "$IMAGE" \
    /smoke/run-rocksdb-jni-smoke-test.sh /hugegraph-server

docker run --detach --platform "linux/$EXPECTED_ARCH" \
    --name "$CONTAINER_NAME" \
    --env HG_SERVER_STARTUP_TIMEOUT="$SERVER_STARTUP_TIMEOUT" \
    --publish 127.0.0.1::8080 \
    "$IMAGE" >/dev/null
CONTAINER_STARTED=true

update_server_url() {
    local port_binding
    local server_port

    port_binding=$(docker port "$CONTAINER_NAME" 8080/tcp | head -n 1)
    if [[ -z "$port_binding" ]]; then
        echo "HugeGraph container has no published port for 8080/tcp" >&2
        return 1
    fi
    server_port=${port_binding##*:}
    SERVER_URL="http://127.0.0.1:$server_port"
}

wait_for_container_server() {
    local deadline=$((SECONDS + CONTAINER_READY_TIMEOUT))
    local running
    while ((SECONDS < deadline)); do
        running=$(docker inspect --format '{{.State.Running}}' "$CONTAINER_NAME")
        if [[ "$running" != "true" ]]; then
            echo "HugeGraph container exited before becoming ready" >&2
            docker logs "$CONTAINER_NAME" >&2
            return 1
        fi
        if curl --silent --show-error --fail \
                --connect-timeout 3 --max-time 5 \
                "$SERVER_URL/versions" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    echo "HugeGraph container did not become ready at $SERVER_URL" >&2
    docker logs "$CONTAINER_NAME" >&2
    return 1
}

wait_for_container_health() {
    local health
    for _ in $(seq 1 60); do
        health=$(docker inspect --format \
            '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
            "$CONTAINER_NAME")
        if [[ "$health" == "healthy" ]]; then
            return 0
        fi
        if [[ "$health" == "missing" ]]; then
            echo "HugeGraph container has no health status" >&2
            return 1
        fi
        sleep 1
    done
    echo "HugeGraph container health did not become healthy" >&2
    docker inspect --format '{{json .State.Health}}' "$CONTAINER_NAME" >&2
    return 1
}

update_server_url
wait_for_container_server
wait_for_container_health
"$TRAVIS_DIR/run-server-e2e-smoke-test.sh" "$SERVER_URL" create "$RUN_ID" | \
    tee "$WORK_DIR/create.log"
grep -q '^server-e2e-smoke-create-ok$' "$WORK_DIR/create.log"
docker restart --time 30 "$CONTAINER_NAME" >/dev/null
update_server_url
wait_for_container_server
wait_for_container_health
"$TRAVIS_DIR/run-server-e2e-smoke-test.sh" "$SERVER_URL" verify "$RUN_ID" | \
    tee "$WORK_DIR/verify.log"
grep -q '^server-e2e-smoke-verify-ok$' "$WORK_DIR/verify.log"

if ! docker logs "$CONTAINER_NAME" > "$CONTAINER_LOG" 2>&1; then
    echo "Failed to read HugeGraph container logs" >&2
    cat "$CONTAINER_LOG" >&2
    exit 1
fi
if grep -Eiq \
   'undefined symbol|UnsatisfiedLinkError|UnsupportedClassVersionError|NoClassDefFoundError' \
   "$CONTAINER_LOG"; then
    echo "Native linkage or Java compatibility error found in container logs" >&2
    cat "$CONTAINER_LOG" >&2
    exit 1
fi

echo "docker-runtime-smoke-$EXPECTED_ARCH-ok"
