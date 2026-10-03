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

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT
WRAPPER="${1:-$SCRIPT_DIR/run-topling-native-diagnostic.sh}"

cat > "$TEST_ROOT/probe.sh" <<'FIXTURE'
#!/bin/bash
set -eu
mode="$1"
echo 'JNI identity fixture: sha256=fixture-only'
if [ "$mode" = probe ]; then
    case "$CASE" in
        prereq-uppercase)
            echo 'ERROR: unexpected native initialization failure'
            echo 'Topling diagnostic phase: complete'
            exit 0 ;;
        optional-warning)
            echo 'WARN: access java.nio.DirectByteBuffer failed: java.lang.NoSuchMethodException: no such constructor: java.nio.DirectByteBuffer.<init>(long,long)void/newInvokeSpecial' ;;
        prereq-zero)
            echo 'Topling diagnostic phase: complete'
            echo 'native.cc:1: unknown: verify(ok) failed: db not closed'
            exit 0 ;;
        prereq-incomplete)
            exit 0 ;;
        prereq-*)
            echo "${CASE#prereq-}"
            exit 1 ;;
    esac
    echo 'Topling diagnostic phase: runtime-check'
    echo 'Topling diagnostic phase: complete'
    echo 'Runtime smoke test passed: provider=topling, mode=probe'
    exit 0
fi
case "$CASE" in
    pass|optional-warning)
        echo 'Topling diagnostic phase: complete'
        exit 0 ;;
    uppercase-error|lowercase-error|bracket-error)
        echo 'Topling diagnostic phase: cf-lifecycle'
        echo 'side_plugin_repo.cc:123: CloseAllDB: verify(iter != end) failed: cfh must in cfh_to_view !'
        case "$CASE" in
            uppercase-error) echo 'ERROR: unexpected native failure' ;;
            lowercase-error) echo 'error: unexpected native failure' ;;
            *) echo '[ERROR] unexpected native failure' ;;
        esac
        exit 134 ;;
    mixed|java-error|unknown-assertion|zero-unknown)
        echo 'Topling diagnostic phase: cf-lifecycle'
        if [ "$CASE" = mixed ] || [ "$CASE" = java-error ]; then
            echo 'side_plugin_repo.cc:123: CloseAllDB: verify(iter != end) failed: cfh must in cfh_to_view !'
        fi
        if [ "$CASE" = java-error ]; then
            echo 'java.lang.IllegalStateException: create CF failed'
        else
            echo 'native.cc:1: unknown: verify(ok) failed: db not closed'
        fi
        if [ "$CASE" = zero-unknown ]; then
            echo 'Topling diagnostic phase: complete'
            exit 0
        fi
        exit 134 ;;
    known|known-cpp|wrong-status|early|silent)
        if [ "$CASE" != early ]; then
            echo 'Topling diagnostic phase: cf-lifecycle'
        fi
        if [ "$CASE" = known-cpp ]; then
            echo 'sideplugin/rockside/src/topling/builtin_plugin_misc.cc:4596: rocksdb::SidePluginRepo::CloseOneDB(rocksdb::DB*, bool)::<lambda(rocksdb::ColumnFamilyHandle*)>: verify(cfh_to_view.end() != iter) failed: cfh must in cfh_to_view !'
        else
            echo 'side_plugin_repo.cc:123: CloseAllDB: verify(iter != end) failed: cfh must in cfh_to_view !'
        fi
        case "$CASE" in
            wrong-status) exit 1 ;;
            silent) exit 0 ;;
            *) exit 134 ;;
        esac ;;
    *)
        echo 'Topling diagnostic phase: runtime-check'
        echo "$CASE"
        # A secondary native abort during cleanup must not hide this failure.
        echo 'side_plugin_repo.cc:123: CloseAllDB: verify(iter != end) failed: cfh must in cfh_to_view !'
        exit 134 ;;
esac
FIXTURE
chmod +x "$TEST_ROOT/probe.sh"

run_case() {
    local name="$1" expected="$2" classification="$3" status=0
    local case_dir="$TEST_ROOT/${1//\//_}"
    CASE="$name" GITHUB_STEP_SUMMARY="$case_dir.summary" bash "$WRAPPER" "$case_dir" "$TEST_ROOT/probe.sh"         > "$case_dir.output" 2>&1 || status=$?
    if [ "$status" -ne "$expected" ]; then
        cat "$case_dir.output"
        echo "FAIL: $name expected status $expected, got $status" >&2
        exit 1
    fi
    grep -Fq "Result: $classification" "$case_dir/report.md"
    cmp "$case_dir/report.md" "$case_dir.summary"
    if [ "$classification" != known-cf-assertion ] &&
       grep -Fq '#212' "$case_dir/report.md"; then
        echo "FAIL: $name was incorrectly attributed to #212" >&2
        exit 1
    fi
    grep -Fq 'JNI identity fixture:' "$case_dir/probe.log"
    echo "PASS: $name"
}

run_case pass 0 passed
run_case known 0 known-cf-assertion
run_case known-cpp 0 known-cf-assertion
run_case optional-warning 0 passed
run_case prereq-uppercase 1 prerequisite-failed
for failure in uppercase-error lowercase-error bracket-error wrong-status early silent mixed java-error unknown-assertion zero-unknown \
    'Error: missing config' \
    'java.lang.NoClassDefFoundError: org/rocksdb/RocksDB' \
    'java.lang.UnsatisfiedLinkError: missing JNI' \
    'Expected native library is not mapped' \
    'initial read returned unexpected data'; do
    run_case "$failure" 1 unknown-failure
done

for failure in \
    'Error: readable ToplingDB Easy Migrate config is required' \
    'java.lang.NoClassDefFoundError: org/rocksdb/RocksDB' \
    'java.lang.UnsatisfiedLinkError: missing JNI' \
    'Expected native library is not mapped' \
    'probe read returned unexpected data'; do
    run_case "prereq-$failure" 1 prerequisite-failed
done

run_case prereq-zero 1 prerequisite-failed
run_case prereq-incomplete 1 prerequisite-failed

# Compile against the selected API without loading native code, then verify that
# a Java close failure is visible before any subsequent native cleanup can abort.
JNI_JAR="$SCRIPT_DIR/../static/lib/topling/rocksdbjni-8.10.2-20260725.141011-1.jar"
javac -cp "$JNI_JAR" -d "$TEST_ROOT/classes" \
    "$SCRIPT_DIR/RocksDBRuntimeSmokeTest.java" \
    "$SCRIPT_DIR/ToplingDiagnosticResourcesTest.java"
java -cp "$TEST_ROOT/classes:$JNI_JAR" ToplingDiagnosticResourcesTest
