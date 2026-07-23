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

TRAVIS_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$TRAVIS_DIR/../../../../.." && pwd)
TEST_MODULE=hugegraph-server/hugegraph-test
WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/hugegraph-maven-profile-check.XXXXXX")

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

cd "$REPO_ROOT"

DEFAULT_PROFILES="$WORK_DIR/default-profiles.txt"
ROCKSDB_ONLY_PROFILES="$WORK_DIR/rocksdb-only-profiles.txt"

mvn help:active-profiles -pl "$TEST_MODULE" -ntp -Dstyle.color=never \
    > "$DEFAULT_PROFILES"
mvn help:active-profiles -pl "$TEST_MODULE" -Drocksdb-only -ntp \
    -Dstyle.color=never > "$ROCKSDB_ONLY_PROFILES"

require_active_profile() {
    local profile=$1
    local profiles_file=$2
    if ! grep -Eq "^[[:space:]]*-[[:space:]]+${profile}[[:space:]]+\\(source:" \
            "$profiles_file"; then
        echo "Required Maven profile is inactive: $profile" >&2
        cat "$profiles_file" >&2
        exit 1
    fi
}

require_inactive_profile() {
    local profile=$1
    local profiles_file=$2
    if grep -Eq "^[[:space:]]*-[[:space:]]+${profile}[[:space:]]+\\(source:" \
            "$profiles_file"; then
        echo "Maven profile should be inactive: $profile" >&2
        cat "$profiles_file" >&2
        exit 1
    fi
}

for profile in core-test all-backends memory; do
    require_active_profile "$profile" "$DEFAULT_PROFILES"
done

for profile in core-test rocksdb-only; do
    require_active_profile "$profile" "$ROCKSDB_ONLY_PROFILES"
done
for profile in all-backends memory; do
    require_inactive_profile "$profile" "$ROCKSDB_ONLY_PROFILES"
done

DEFAULT_POM="$WORK_DIR/default-effective-pom.xml"
ROCKSDB_POM="$WORK_DIR/rocksdb-effective-pom.xml"
mvn help:effective-pom -pl "$TEST_MODULE" -ntp -Dstyle.color=never \
    -Doutput="$DEFAULT_POM"
mvn help:effective-pom -pl "$TEST_MODULE" -P rocksdb -ntp \
    -Dstyle.color=never -Doutput="$ROCKSDB_POM"

DEFAULT_MODEL=$(awk '/^[[:space:]]*<profiles>/{exit} {print}' "$DEFAULT_POM")
ROCKSDB_MODEL=$(awk '/^[[:space:]]*<profiles>/{exit} {print}' "$ROCKSDB_POM")
CORE_EXECUTION=$(awk '
    /<execution>/ {
        in_execution = 1
        block = $0
        next
    }
    in_execution {
        block = block "\n" $0
    }
    in_execution && /<\/execution>/ {
        if (block ~ /<id>core-test<\/id>/) {
            print block
            exit
        }
        in_execution = 0
        block = ""
    }
' "$DEFAULT_POM")

for expected in '<backend>memory</backend>' \
                '<serializer>text</serializer>'; do
    grep -Fq "$expected" <<< "$DEFAULT_MODEL" || {
        echo "Default test configuration is missing: $expected" >&2
        exit 1
    }
done

for expected in '<phase>test</phase>' '<goal>test</goal>'; do
    grep -Fq "$expected" <<< "$CORE_EXECUTION" || {
        echo "Default Core Surefire execution is missing: $expected" >&2
        exit 1
    }
done

for expected in '<backend>rocksdb</backend>' \
                '<serializer>binary</serializer>'; do
    grep -Fq "$expected" <<< "$ROCKSDB_MODEL" || {
        echo "Explicit RocksDB configuration is missing: $expected" >&2
        exit 1
    }
done

echo "Default and explicit Maven test profiles are valid"
