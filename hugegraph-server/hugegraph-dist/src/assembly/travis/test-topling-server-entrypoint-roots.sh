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

# Real marker-helper filesystem checks; run on Linux (no JVM/native runtime).
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
reset_fixture() {
    rm -rf "$TEST_ROOT/server"
    FIXTURE="$TEST_ROOT/server"
    mkdir -p "$FIXTURE/bin" "$FIXTURE/conf/graphs" "$FIXTURE/topling-data" \
             "$FIXTURE/extra root/data" "$FIXTURE/extra root/wal"
    cp "${ENTRYPOINT_SOURCE:-$DIST_ROOT/docker/docker-entrypoint.sh}" "$FIXTURE/entrypoint.sh"
    cp "$DIST_ROOT/src/assembly/static/bin/"{rocksdb-server-config,verify-rocksdb-provider}.sh "$FIXTURE/bin/"
    printf '%s\n' '#!/bin/bash' 'touch init-reached' 'exit 37' > "$FIXTURE/bin/init-store.sh"
    printf '%s\n' '#!/bin/bash' 'exit 0' > "$FIXTURE/bin/wait-storage.sh"
    chmod +x "$FIXTURE/bin/"*.sh
    printf '%s\n' 'graphs=./conf/graphs' > "$FIXTURE/conf/rest-server.properties"
    printf '%s\n' 'backend=rocksdb' 'rocksdb.provider=rocksdb' > "$FIXTURE/conf/graphs/hugegraph.properties"
    printf '%s\n' 'backend=rocksdb' 'rocksdb.provider=topling' \
        'rocksdb.data_path=./extra root/data' 'rocksdb.wal_path=./extra root/wal' \
        > "$FIXTURE/conf/graphs/extra.properties"
}
marker() {
    printf '%s\n' format=1 component=server "provider=$2" > "$1/.hugegraph-rocksdb-provider"
}
run_entrypoint() {
    local status=0
    (cd "$FIXTURE"; HG_SERVER_ROCKSDB_PROVIDER=topling HG_SERVER_ENFORCE_PROVIDER_MARKER=true \
        bash ./entrypoint.sh) > "$TEST_ROOT/output" 2>&1 || status=$?
    return "$status"
}
expect_rejected() {
    local name="$1" message="$2"
    if run_entrypoint; then fail "$name unexpectedly succeeded"; fi
    [ ! -e "$FIXTURE/init-reached" ] || { cat "$TEST_ROOT/output"; fail "$name reached init-store"; }
    grep -Fq "$message" "$TEST_ROOT/output" || { cat "$TEST_ROOT/output"; fail "$name missing diagnostic"; }
    echo "PASS: $name"
}
expect_init() {
    local status=0
    run_entrypoint || status=$?
    [ "$status" = 37 ] && [ -f "$FIXTURE/init-reached" ] || {
        cat "$TEST_ROOT/output"; fail "valid configuration did not reach init-store";
    }
}

for backend in ROCKSDB rocksdbsst; do
    reset_fixture
    export HG_SERVER_BACKEND="$backend"
    expect_init
    unset HG_SERVER_BACKEND
    grep -qx "backend=$backend" "$FIXTURE/conf/graphs/hugegraph.properties"
    grep -qx "rocksdb.data_path=$FIXTURE/topling-data/data" "$FIXTURE/conf/graphs/hugegraph.properties"
    grep -qx "rocksdb.wal_path=$FIXTURE/topling-data/wal" "$FIXTURE/conf/graphs/hugegraph.properties"
    grep -qx provider=topling "$FIXTURE/topling-data/.hugegraph-rocksdb-provider"
    echo "PASS: Docker primary $backend generates and validates Topling roots"
done

reset_fixture
printf '%s\n' 'backend=RoCkSdB' 'rocksdb.provider=topling' \
    'rocksdb.data_path=./extra root/data' 'rocksdb.wal_path=./extra root/wal' \
    > "$FIXTURE/conf/graphs/extra.properties"
marker "$FIXTURE/extra root" rocksdb
expect_rejected "mixed-case backend roots cannot bypass markers" "provider marker mismatch"

reset_fixture
marker "$FIXTURE/extra root" rocksdb
printf '%s\n' existing-data > "$FIXTURE/extra root/data/SENTINEL"
cp "$FIXTURE/extra root/.hugegraph-rocksdb-provider" "$TEST_ROOT/before-marker"
expect_rejected "extra graph wrong ancestor marker" "provider marker mismatch"
cmp "$TEST_ROOT/before-marker" "$FIXTURE/extra root/.hugegraph-rocksdb-provider"
grep -qx existing-data "$FIXTURE/extra root/data/SENTINEL"

reset_fixture
marker "$FIXTURE/extra root/data" topling
marker "$FIXTURE/extra root/wal" rocksdb
expect_rejected "separate WAL provider mismatch" "provider marker mismatch"

reset_fixture
marker "$FIXTURE/extra root" topling
mkdir -p "$FIXTURE/foreign"
marker "$FIXTURE/foreign" rocksdb
rmdir "$FIXTURE/extra root/data"
ln -s "$FIXTURE/foreign" "$FIXTURE/extra root/data"
expect_rejected "marked ancestor cannot hide data symlink" "contains a symlink"
grep -qx provider=rocksdb "$FIXTURE/foreign/.hugegraph-rocksdb-provider"

reset_fixture
printf '%s\n' 'backend=rocksdb' > "$FIXTURE/conf/graphs/implicit.properties"
expect_rejected "mixed explicit and default providers" "conflicting rocksdb.provider values"

reset_fixture
printf '%s\n' 'rocksdb.data_disks=g/vertex:/disk' >> "$FIXTURE/conf/graphs/extra.properties"
expect_rejected "unsupported additional disks" "does not support rocksdb.data_disks"

reset_fixture
printf '%s\n' 'include=hidden.properties' >> "$FIXTURE/conf/graphs/extra.properties"
expect_rejected "unsupported config includes" "property includes are unsupported"

for graph_path in './custom graphs' absolute; do
    reset_fixture
    mv "$FIXTURE/conf/graphs" "$FIXTURE/custom graphs"
    if [ "$graph_path" = absolute ]; then graph_path="$FIXTURE/custom graphs"; fi
    printf 'graphs=%s\n' "$graph_path" > "$FIXTURE/conf/rest-server.properties"
    expect_init
    grep -qx provider=topling "$FIXTURE/extra root/data/.hugegraph-rocksdb-provider"
    grep -qx provider=topling "$FIXTURE/extra root/wal/.hugegraph-rocksdb-provider"
    grep -qx rocksdb.provider=topling "$FIXTURE/custom graphs/hugegraph.properties"
    echo "PASS: custom graphs $graph_path validates data and WAL before init"
done

reset_fixture
marker "$FIXTURE/extra root" topling
expect_init
echo "PASS: matching deployment ancestor accepts configured data/WAL children"

# Direct launch and init-store run the real marker verifier before Java/native.
prepare_direct() {
    reset_fixture
    cp "$DIST_ROOT/src/assembly/static/bin/"{preload-topling,hugegraph-server,init-store,util}.sh "$FIXTURE/bin/"
    mkdir -p "$FIXTURE/lib" "$FIXTURE/plugins" "$FIXTURE/ext" "$FIXTURE/logs" "$FIXTURE/fake-bin"
    # shellcheck disable=SC2016
    printf '%s\n' '#!/bin/bash' 'touch "$JAVA_REACHED"' 'exit 98' > "$FIXTURE/fake-bin/java"
    # shellcheck disable=SC2016
    printf '%s\n' '#!/bin/bash' 'case "$1" in -s) echo Linux;; -m) echo x86_64;; *) echo Linux;; esac' \
        > "$FIXTURE/fake-bin/uname"
    chmod +x "$FIXTURE/fake-bin/"* "$FIXTURE/bin/"*.sh
    # Custom graph directory and mixed-case backend must reach the same guard.
    mv "$FIXTURE/conf/graphs" "$FIXTURE/custom graphs"
    rm "$FIXTURE/custom graphs/extra.properties"
    printf '%s\n' 'graphs=./custom graphs' > "$FIXTURE/conf/rest-server.properties"
    printf '%s\n' 'backend=RoCkSdB' 'rocksdb.provider=topling' \
        'rocksdb.data_path=./extra root/data' 'rocksdb.wal_path=./extra root/wal' \
        > "$FIXTURE/custom graphs/hugegraph.properties"
}
expect_direct_rejected() {
    local launcher="$1" name="$2" message="$3" status=0
    local args=()
    if [ "$launcher" = hugegraph-server ]; then
        args=(conf/gremlin-server.yaml conf/rest-server.properties true)
    fi
    (cd "$FIXTURE"; JAVA_HOME='' STDOUT_MODE=true JAVA_REACHED="$FIXTURE/java-reached" \
        PATH="$FIXTURE/fake-bin:$PATH" bash "./bin/$launcher.sh" "${args[@]}") \
        > "$TEST_ROOT/output" 2>&1 || status=$?
    [ "$status" != 0 ] || fail "$name unexpectedly succeeded"
    [ ! -e "$FIXTURE/java-reached" ] || { cat "$TEST_ROOT/output"; fail "$name reached Java"; }
    grep -Fq "$message" "$TEST_ROOT/output" || { cat "$TEST_ROOT/output"; fail "$name missing diagnostic"; }
    echo "PASS: $name"
}
for launcher in hugegraph-server init-store; do
    prepare_direct
    marker "$FIXTURE/extra root" rocksdb
    printf '%s\n' original-data > "$FIXTURE/extra root/data/SENTINEL"
    cp "$FIXTURE/extra root/.hugegraph-rocksdb-provider" "$TEST_ROOT/original-marker"
    expect_direct_rejected "$launcher" "$launcher rejects conflicting ancestor marker" "provider marker mismatch"
    cmp "$TEST_ROOT/original-marker" "$FIXTURE/extra root/.hugegraph-rocksdb-provider"
    grep -qx original-data "$FIXTURE/extra root/data/SENTINEL"

    prepare_direct
    printf '%s\n' original-data > "$FIXTURE/extra root/data/SENTINEL"
    expect_direct_rejected "$launcher" "$launcher rejects unmarked nonempty Topling root" \
        "refusing unmarked non-empty data path"
    grep -qx original-data "$FIXTURE/extra root/data/SENTINEL"
    [ ! -e "$FIXTURE/extra root/data/.hugegraph-rocksdb-provider" ] || fail "rejected data was marked"

done
