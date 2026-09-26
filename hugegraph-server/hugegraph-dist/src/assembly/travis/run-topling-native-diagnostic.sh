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

LOG_DIR="${1:?Usage: $0 <log-dir> <runtime-command> [args...]}"
shift
if [ "$#" -eq 0 ]; then
    echo 'Error: runtime command is required' >&2
    exit 1
fi
mkdir -p "$LOG_DIR"

report() {
    local result="$1" status="$2" exit_status="$3"
    {
        echo '### Topling native lifecycle diagnostic'
        echo
        echo "Result: $result"
        echo "Runtime exit status: $status"
        if [ "$result" = known-cf-assertion ]; then
            echo 'Verified runtime prerequisites passed; the synthetic CF phase hit'
            echo 'the exact known assertion tracked by hugegraph/hugegraph#212.'
            echo 'This diagnostic does not replace Linux service lifecycle acceptance.'
        fi
        echo 'Full probe/lifecycle logs and selected JNI SHA-256 identities are retained in the diagnostic artifact.'
    } > "$LOG_DIR/report.md"
    cat "$LOG_DIR/report.md"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        cat "$LOG_DIR/report.md" >> "$GITHUB_STEP_SUMMARY"
    fi
    exit "$exit_status"
}

failure_pattern='verify\(.*\) failed:|assertion.*failed|db not closed|Exception|Error:|Error([[:space:]]|\]|$)|Fatal'
# This producer warning means the faster DirectByteBuffer constructor is absent.
# It is not a thrown probe failure; successful read/iteration/close is still required.
optional_warning='^WARN: access java[.]nio[.]DirectByteBuffer failed: java[.]lang[.]NoSuchMethodException: no such constructor: java[.]nio[.]DirectByteBuffer[.]<init>\(long,long\)void/newInvokeSpecial$'
diagnostic_errors() {
    grep -Ev "$optional_warning" "$1" | grep -Ei "$failure_pattern" || true
}

# Run the mandatory probe in a separate process. Even a known assertion here
# fails: only the subsequent synthetic CF sequence has a narrow waiver.
set +e
"$@" probe 2>&1 | tee "$LOG_DIR/probe.log"
probe_status=("${PIPESTATUS[@]}")
set -e
if [ "${probe_status[1]}" -ne 0 ]; then
    report log-capture-failed "${probe_status[1]}" 1
fi
probe_errors=$(diagnostic_errors "$LOG_DIR/probe.log")
probe_phase=$(sed -n 's/^Topling diagnostic phase: //p' "$LOG_DIR/probe.log" | tail -1)
if [ "${probe_status[0]}" -ne 0 ] || [ "$probe_phase" != complete ] ||
   [ -n "$probe_errors" ]; then
    report prerequisite-failed "${probe_status[0]}" 1
fi

set +e
"$@" lifecycle 2>&1 | tee "$LOG_DIR/lifecycle.log"
lifecycle_status=("${PIPESTATUS[@]}")
set -e
status="${lifecycle_status[0]}"
if [ "${lifecycle_status[1]}" -ne 0 ]; then
    report log-capture-failed "${lifecycle_status[1]}" 1
fi
phase=$(sed -n 's/^Topling diagnostic phase: //p' "$LOG_DIR/lifecycle.log" | tail -1)
known_pattern=': verify\(.*\) failed: cfh must in cfh_to_view !$'
# Do not waive a known abort accompanied by a different failure (including
# a Java failure which triggers a second assertion while unwinding resources).
unknown_errors=$(diagnostic_errors "$LOG_DIR/lifecycle.log" |
                 grep -Ev "$known_pattern" || true)
if [ "$status" -eq 0 ] && [ "$phase" = complete ] && [ -z "$unknown_errors" ] &&
   ! grep -Eq "$known_pattern" "$LOG_DIR/lifecycle.log"; then
    report passed "$status" 0
fi
if [ "$status" -eq 134 ] && [ "$phase" = cf-lifecycle ] && [ -z "$unknown_errors" ] &&
   grep -Eq "$known_pattern" "$LOG_DIR/lifecycle.log"; then
    report known-cf-assertion "$status" 0
fi
report unknown-failure "$status" 1
