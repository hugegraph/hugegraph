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

# Portable standard ownership, plus real-helper concurrency when run on Linux.
set -Eeuo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_HELPER="$(cd "$SCRIPT_DIR/../static/bin" && pwd)/rocksdb-server-config.sh"
MARKER_HELPER="$(dirname "$CONFIG_HELPER")/verify-rocksdb-provider.sh"
TEST_ROOT=$(mktemp -d)
TEST_ROOT=$(cd -P "$TEST_ROOT" && pwd)
standard_pid=""
topling_pid=""
cleanup() {
    local pid
    for pid in "$standard_pid" "$topling_pid"; do
        [ -n "$pid" ] || continue
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    done
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT
# shellcheck source=/dev/null
source "$CONFIG_HELPER"
fail() { echo "FAIL: $*" >&2; exit 1; }
claim_standard() { server_verify_storage_path "$TEST_ROOT" rocksdb "$1" false; }

claim_standard "$TEST_ROOT/new/data"
grep -qx provider=rocksdb "$TEST_ROOT/new/data/.hugegraph-rocksdb-provider"
echo "PASS: portable standard startup atomically claims new roots"
mkdir -p "$TEST_ROOT/legacy"
printf '%s\n' existing-data > "$TEST_ROOT/legacy/SENTINEL"
claim_standard "$TEST_ROOT/legacy"
[ ! -e "$TEST_ROOT/legacy/.hugegraph-rocksdb-provider" ] || fail "legacy root was relabeled"
grep -qx existing-data "$TEST_ROOT/legacy/SENTINEL"
echo "PASS: nonempty unmarked standard legacy data remains unchanged"

mkdir -p "$TEST_ROOT/foreign"
printf '%s\n' format=1 component=server provider=topling > "$TEST_ROOT/foreign/.hugegraph-rocksdb-provider"
cp "$TEST_ROOT/foreign/.hugegraph-rocksdb-provider" "$TEST_ROOT/foreign-before"
if claim_standard "$TEST_ROOT/foreign/missing" > "$TEST_ROOT/output" 2>&1; then fail "foreign ancestor accepted"; fi
[ ! -e "$TEST_ROOT/foreign/missing" ] || fail "foreign ancestor was modified"
cmp "$TEST_ROOT/foreign-before" "$TEST_ROOT/foreign/.hugegraph-rocksdb-provider"
echo "PASS: conflicting ancestor rejected before directory creation"

mkdir -p "$TEST_ROOT/pending"
touch "$TEST_ROOT/pending/.provider-marker.pending"
if claim_standard "$TEST_ROOT/pending" > "$TEST_ROOT/output" 2>&1; then fail "in-progress ownership accepted"; fi
grep -Fq 'initialization is in progress' "$TEST_ROOT/output"
[ ! -e "$TEST_ROOT/pending/.hugegraph-rocksdb-provider" ] || fail "pending ownership was replaced"
echo "PASS: in-progress Topling marker cannot be treated as legacy data"

mkdir -p "$TEST_ROOT/partial"
touch "$TEST_ROOT/partial/.hugegraph-rocksdb-provider"
if claim_standard "$TEST_ROOT/partial" > "$TEST_ROOT/output" 2>&1; then fail "partial marker accepted"; fi
[ ! -s "$TEST_ROOT/partial/.hugegraph-rocksdb-provider" ] || fail "partial marker was overwritten"
echo "PASS: interrupted standard marker stays fail-closed"

# Pause immediately after an empty-directory scan, before either provider can
# publish ownership. The barrier is outside the storage root being checked.
mkdir -p "$TEST_ROOT/fake-bin" "$TEST_ROOT/barrier"
REAL_FIND=$(command -v find)
export REAL_FIND
cat > "$TEST_ROOT/fake-bin/find" <<'SH'
#!/bin/bash
status=0
"$REAL_FIND" "$@" || status=$?
case " $* " in
    *' lost+found '*)
        touch "$BARRIER_DIR/$OWNER_ACTOR.ready"
        while [ ! -f "$BARRIER_DIR/release" ]; do sleep 0.01; done
        ;;
esac
exit "$status"
SH
chmod +x "$TEST_ROOT/fake-bin/find"
wait_ready() {
    local actor="$1" attempt
    for ((attempt = 0; attempt < 1000; attempt++)); do
        [ ! -f "$TEST_ROOT/barrier/$actor.ready" ] || return 0
        sleep 0.01
    done
    fail "$actor did not reach ownership barrier"
}
run_standard_actor() {
    PATH="$TEST_ROOT/fake-bin:$PATH" OWNER_ACTOR=standard BARRIER_DIR="$TEST_ROOT/barrier" \
        bash -c 'source "$1"; server_verify_storage_path "$2" rocksdb "$3" false' \
        _ "$CONFIG_HELPER" "$TEST_ROOT" "$TEST_ROOT/race"
}
mkdir "$TEST_ROOT/race"
run_standard_actor > "$TEST_ROOT/standard.log" 2>&1 &
standard_pid=$!
wait_ready standard
printf '%s\n' format=1 component=server provider=topling > "$TEST_ROOT/race/.provider-marker.fixture"
mv -n "$TEST_ROOT/race/.provider-marker.fixture" "$TEST_ROOT/race/.hugegraph-rocksdb-provider"
touch "$TEST_ROOT/barrier/release"
standard_status=0
wait "$standard_pid" || standard_status=$?
standard_pid=""
[ "$standard_status" != 0 ] || fail "standard opened after Topling won the empty-root race"
grep -qx provider=topling "$TEST_ROOT/race/.hugegraph-rocksdb-provider"
echo "PASS: standard loses cleanly when Topling claims after its empty scan"

if [ "$(uname -s)" = Linux ]; then
    rm -rf "$TEST_ROOT/race" "$TEST_ROOT/barrier"
    mkdir "$TEST_ROOT/race" "$TEST_ROOT/barrier"
    run_standard_actor > "$TEST_ROOT/standard.log" 2>&1 &
    standard_pid=$!
    PATH="$TEST_ROOT/fake-bin:$PATH" OWNER_ACTOR=topling BARRIER_DIR="$TEST_ROOT/barrier" \
        bash "$MARKER_HELPER" server topling "$TEST_ROOT/race" true > "$TEST_ROOT/topling.log" 2>&1 &
    topling_pid=$!
    wait_ready standard
    wait_ready topling
    touch "$TEST_ROOT/barrier/release"
    standard_status=0
    topling_status=0
    wait "$standard_pid" || standard_status=$?
    standard_pid=""
    wait "$topling_pid" || topling_status=$?
    topling_pid=""
    if [ "$standard_status" = 0 ] && [ "$topling_status" = 0 ]; then fail "both providers acquired the empty root"; fi
    if [ "$standard_status" != 0 ] && [ "$topling_status" != 0 ]; then
        cat "$TEST_ROOT/standard.log" "$TEST_ROOT/topling.log"
        fail "neither provider acquired the empty root"
    fi
    if [ "$standard_status" = 0 ]; then provider=rocksdb; else provider=topling; fi
    grep -qx "provider=$provider" "$TEST_ROOT/race/.hugegraph-rocksdb-provider"
    echo "PASS: simultaneous standard and real Topling helper admit exactly one provider"

    # Docker owns a deployment parent; bare launch owns configured children.
    # A parent claim started first must notice a child created after its scan.
    rm -rf "$TEST_ROOT/race" "$TEST_ROOT/barrier"
    mkdir "$TEST_ROOT/race" "$TEST_ROOT/barrier"
    PATH="$TEST_ROOT/fake-bin:$PATH" OWNER_ACTOR=topling BARRIER_DIR="$TEST_ROOT/barrier" \
        bash "$MARKER_HELPER" server topling "$TEST_ROOT/race" true > "$TEST_ROOT/topling.log" 2>&1 &
    topling_pid=$!
    wait_ready topling
    claim_standard "$TEST_ROOT/race/data"
    touch "$TEST_ROOT/barrier/release"
    topling_status=0
    wait "$topling_pid" || topling_status=$?
    topling_pid=""
    [ "$topling_status" != 0 ] || fail "Topling parent hid an already claimed standard child"
    [ ! -e "$TEST_ROOT/race/.hugegraph-rocksdb-provider" ] || fail "conflicting parent was marked"
    grep -qx provider=rocksdb "$TEST_ROOT/race/data/.hugegraph-rocksdb-provider"
    grep -Fq 'data path changed while initializing provider marker' "$TEST_ROOT/topling.log"
    echo "PASS: Topling deployment parent cannot override a newly claimed standard child"
else
    echo "SKIP: real Topling helper concurrency requires Linux"
fi
