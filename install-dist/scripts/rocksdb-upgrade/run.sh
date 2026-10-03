#!/usr/bin/env bash
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
if [[ $# != 4 ]]; then
    echo "Usage: $0 <6.29.5.jar> <7.7.3.jar> <8.10.2.jar> <new-output-directory>" >&2
    exit 2
fi
script_dir=$(cd "$(dirname "$0")" && pwd)
absolute_file() {
    [[ -f "$1" ]] || { echo "Missing JAR: $1" >&2; exit 2; }
    printf '%s/%s\n' "$(cd "$(dirname "$1")" && pwd)" "$(basename "$1")"
}
old6=$(absolute_file "$1")
old7=$(absolute_file "$2")
new=$(absolute_file "$3")
# mkdir must fail for an existing output path; never remove a previous run.
mkdir "$4"
output=$(cd "$4" && pwd)
mkdir "$output/classes" "$output/empty-native-path"
exec > >(tee "$output/run.log") 2>&1
trap 'result=$?; echo "RESULT exit=$result output=$output"' EXIT
run() {
    local result
    printf 'COMMAND '
    printf '%q ' "$@"
    printf '\n'
    if "$@"; then
        result=0
    else
        result=$?
    fi
    echo "COMMAND_EXIT=$result"
    return "$result"
}
# Avoid inherited classpaths, VM injection and native preload overrides.
for variable in JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS LD_PRELOAD DYLD_INSERT_LIBRARIES; do
    if [[ -n "${!variable:-}" ]]; then
        echo "Unset $variable before running this standard-JNI fixture" >&2
        exit 2
    fi
done
run javac -cp "$old6" -d "$output/classes" "$script_dir/RocksDBUpgradeFixture.java"
for version in 6.29.5 7.7.3; do
    old="$old6"
    [[ "$version" != 7.7.3 ]] || old="$old7"
    case_dir="$output/$version"
    mkdir "$case_dir"
    run java -Djava.library.path="$output/empty-native-path" -cp "$output/classes:$old" \
        RocksDBUpgradeFixture seed "$case_dir/original" "$version" "$old"
    run cp -R "$case_dir/original" "$case_dir/upgraded"
    run java -Djava.library.path="$output/empty-native-path" -cp "$output/classes:$new" \
        RocksDBUpgradeFixture upgrade "$case_dir/upgraded" 8.10.2 "$new"
    run java -Djava.library.path="$output/empty-native-path" -cp "$output/classes:$new" \
        RocksDBUpgradeFixture verify "$case_dir/upgraded" 8.10.2 "$new"
done
echo "PASS upgradePairs=2 JVMPhases=6 skipped=0 scope=JNI-fixture-only"
