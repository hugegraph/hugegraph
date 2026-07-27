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

# Contract tests for the startup port preflight in bin/util.sh.
#
# The preflight is best effort: the server's own bind is authoritative.  These
# tests pin the three-state contract (busy / free / unknown) rather than the
# internals of any one probe.
#
# TODO(test-check-port): the Linux and BSD detection branches are both driven
# by mocked tool output, so on any one runner only the host's own branch is
# ever exercised against a real kernel.  The single real-listener case covers
# whichever OS the job runs on.  Closing this needs the suite to run on both
# a Linux and a macOS runner, which CI already does for the server job.

set -u

STATIC_DIR="${1:-hugegraph-server/hugegraph-dist/src/assembly/static}"
UTIL_SH="$STATIC_DIR/bin/util.sh"

if [[ ! -f "$UTIL_SH" ]]; then
    echo "SKIP: util.sh not found at $UTIL_SH"
    exit 0
fi

# shellcheck source=/dev/null
source "$UTIL_SH"

# Sections run in subshells so their command overrides stay isolated, which
# means results have to be tallied through a file rather than a variable.
RESULTS=$(mktemp)

pass() {
    echo "  PASS  $1"
    echo "P" >> "$RESULTS"
}

fail() {
    echo "  FAIL  $1"
    echo "        $2"
    echo "F" >> "$RESULTS"
}

expect() {
    # expect <name> <expected> <actual>
    if [[ "$2" == "$3" ]]; then
        pass "$1"
    else
        fail "$1" "expected '$2', got '$3'"
    fi
}

echo ""
echo "check_port contract tests ($UTIL_SH)"

# --------------------------------------------------------------------------
echo ""
echo "1. URL to port"
# "<url>|<expected>", where SKIP means "no usable port, preflight is skipped".
url_cases=(
    'http://127.0.0.1:8080|8080'
    'HTTP://127.0.0.1:8080|8080'
    'http://127.0.0.1|80'
    'https://127.0.0.1|443'
    '127.0.0.1:8080|8080'
    'http://127.0.0.1:8080/path:9090|8080'
    'http://127.0.0.1:8080?probe=x|8080'
    'http://127.0.0.1:8080#frag|8080'
    'http://user:pass@127.0.0.1:8080|8080'
    'http://user@127.0.0.1:8080|8080'
    'http://[::1]:8080|8080'
    'http://user:pass@[::1]:8080|8080'
    '[::1]:8080|8080'
    'https://[::1]|443'
    'http://127.0.0.1:08080|8080'
    '::1:8080|SKIP'
    'http://127.0.0.1:abc|SKIP'
    'http://127.0.0.1:0|SKIP'
    'http://127.0.0.1:70000|SKIP'
    '127.0.0.1|SKIP'
)
for case in "${url_cases[@]}"; do
    url="${case%|*}"
    want="${case##*|}"
    got=$(parse_port_from_url "  $url  " 2>/dev/null) || got="SKIP"
    expect "$url" "$want" "$got"
done

# --------------------------------------------------------------------------
echo ""
echo "2. Linux: ss busy / free / failure"
(
    uname() { echo "Linux"; }
    command_available() { [[ "$1" == "ss" ]]; }
    SS_OUT=""
    SS_RC=0
    ss() { printf '%s' "$SS_OUT"; return "$SS_RC"; }

    SS_OUT='LISTEN 0 4096 0.0.0.0:8080 0.0.0.0:*'
    expect "listener on 8080 is busy" "busy" "$(port_listen_state 8080)"

    SS_OUT='LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*'
    expect "no listener on 8080 is free" "free" "$(port_listen_state 8080)"

    # The port must come from the local-address field, not an IPv6 hextet.
    SS_OUT='LISTEN 0 128 [2001:db8:8080::1]:9090 [::]:*'
    expect "IPv6 hextet 8080 is not the port" "free" "$(port_listen_state 8080)"

    SS_OUT='LISTEN 0 128 [::]:8080 [::]:*'
    expect "IPv6 wildcard listener is busy" "busy" "$(port_listen_state 8080)"

    # A tool that runs but fails proves nothing.
    SS_OUT=''
    SS_RC=1
    expect "ss failure is unknown" "unknown" "$(port_listen_state 8080)"

    # Success with an unusable table proves nothing either.
    SS_RC=0
    SS_OUT=''
    expect "ss empty output is unknown" "unknown" "$(port_listen_state 8080)"

    SS_OUT='some diagnostic banner'
    expect "ss unparseable output is unknown" "unknown" "$(port_listen_state 8080)"

)

# --------------------------------------------------------------------------
echo ""
echo "3. macOS/BSD: LISTEN versus ESTABLISHED"
(
    uname() { echo "Darwin"; }
    command_available() { [[ "$1" == "netstat" ]]; }
    NS_OUT=""
    netstat() { printf '%s' "$NS_OUT"; }

    NS_OUT='Active Internet connections (including servers)
Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)
tcp4       0      0  *.8080                 *.*                    LISTEN'
    expect "BSD LISTEN on 8080 is busy" "busy" "$(port_listen_state 8080)"

    # An outbound connection to :443 is not a local listener on 443.  This is
    # the false positive that blocked startup on macOS.
    NS_OUT='Active Internet connections (including servers)
Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)
tcp4       0      0  192.168.1.3.60320      44.195.16.138.443      ESTABLISHED
tcp4       0      0  *.22                   *.*                    LISTEN'
    expect "BSD ESTABLISHED peer :443 is free" "free" "$(port_listen_state 443)"

    # A socket lingering in TIME_WAIT holds the port in its *local* address but
    # is not a listener, so only the connection state can tell them apart.
    NS_OUT='Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)
tcp4       0      0  127.0.0.1.8080         127.0.0.1.51000        TIME_WAIT
tcp4       0      0  *.22                   *.*                    LISTEN'
    expect "BSD TIME_WAIT on local 8080 is free" "free" "$(port_listen_state 8080)"

    NS_OUT='Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)
tcp6       0      0  ::1.8080               *.*                    LISTEN'
    expect "BSD IPv6 LISTEN is busy" "busy" "$(port_listen_state 8080)"

    NS_OUT='Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)'
    expect "BSD header only is unknown" "unknown" "$(port_listen_state 8080)"

)

# --------------------------------------------------------------------------
echo ""
echo "4. Unknown never blocks startup"
(
    uname() { echo "Linux"; }
    command_available() { return 1; }

    expect "no probe tool is unknown" "unknown" "$(port_listen_state 8080)"

    # check_port must warn and let the server perform the authoritative bind.
    err=$( (check_port "http://127.0.0.1:8080") 2>&1 >/dev/null )
    rc=$?
    expect "unknown exits 0" "0" "$rc"
    if [[ "$err" == *"could not determine"* ]]; then
        pass "unknown warns on stderr"
    else
        fail "unknown warns on stderr" "stderr was: $err"
    fi

)

# --------------------------------------------------------------------------
echo ""
echo "5. Real listener on an ephemeral port"
if command -v python3 >/dev/null 2>&1; then
    PORT_FILE=$(mktemp)
    python3 -c '
import socket, sys, time
s = socket.socket()
s.bind(("127.0.0.1", 0))
s.listen(1)
print(s.getsockname()[1])
sys.stdout.flush()
time.sleep(30)
' > "$PORT_FILE" &
    PY_PID=$!
    trap 'kill "$PY_PID" 2>/dev/null; rm -f "$PORT_FILE"' EXIT

    BOUND=""
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        BOUND=$(head -1 "$PORT_FILE" 2>/dev/null)
        [[ -n "$BOUND" ]] && break
        sleep 0.5
    done

    if [[ -z "$BOUND" ]] || ! kill -0 "$PY_PID" 2>/dev/null; then
        fail "listener bound an ephemeral port" "child did not report a port"
    else
        pass "listener bound an ephemeral port ($BOUND)"
        expect "bound port is busy" "busy" "$(port_listen_state "$BOUND")"
        (check_port "http://127.0.0.1:$BOUND" >/dev/null 2>&1)
        expect "check_port exits 1 on the bound port" "1" "$?"

        # A port that was bound and released must not read as busy.
        FREE=$(python3 -c 'import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
p = s.getsockname()[1]
s.close()
print(p)')
        expect "released port is not busy" "0" "$([[ "$(port_listen_state "$FREE")" == "busy" ]] && echo 1 || echo 0)"
    fi

    kill "$PY_PID" 2>/dev/null
    wait "$PY_PID" 2>/dev/null
    rm -f "$PORT_FILE"
    trap - EXIT
else
    echo "  SKIP  real listener test: python3 not available"
fi

# --------------------------------------------------------------------------
echo ""
echo "6. download() rename failure is reported"
(
    WORK=$(mktemp -d)
    trap 'rm -rf "$WORK"' EXIT

    command_available() { [[ "$1" == "curl" ]]; }
    curl() {
        # Write to the -o destination so a temp file exists to rename.
        local out=""
        while [[ $# -gt 0 ]]; do
            [[ "$1" == "-o" ]] && { out="$2"; shift; }
            shift
        done
        echo "payload" > "$out"
        return 0
    }
    # Simulate a rename that fails (read-only target, cross-device, ...).
    mv() { return 1; }

    if download "$WORK" "https://example.com/pkg.tar.gz"; then
        fail "download reports rename failure" "download returned 0 after mv failed"
    else
        pass "download reports rename failure"
    fi

    if [[ -e "$WORK/pkg.tar.gz" ]]; then
        fail "failed rename leaves no destination" "$WORK/pkg.tar.gz exists"
    else
        pass "failed rename leaves no destination"
    fi

    leftover=$(find "$WORK" -name '.pkg.tar.gz.*' 2>/dev/null | wc -l | tr -d ' ')
    expect "failed rename cleans its temp file" "0" "$leftover"

)

# --------------------------------------------------------------------------
echo ""
echo "7. Startup probe is bounded"
(
    WORK=$(mktemp -d)
    cd "$WORK" || exit 1
    trap 'cd /; rm -rf "$WORK"' EXIT

    ARGS_FILE="$WORK/curl-args"
    curl() { echo "$*" >> "$ARGS_FILE"; echo "000"; return 28; }
    process_status() { return 0; }
    ps() { return 0; }

    wait_for_startup "$$" "test-server" "http://127.0.0.1:1" 1 >/dev/null 2>&1

    if grep -q -- "--max-time" "$ARGS_FILE" 2>/dev/null; then
        pass "startup probe passes --max-time"
    else
        fail "startup probe passes --max-time" "args were: $(cat "$ARGS_FILE" 2>/dev/null)"
    fi
    if grep -q -- "--connect-timeout" "$ARGS_FILE" 2>/dev/null; then
        pass "startup probe passes --connect-timeout"
    else
        fail "startup probe passes --connect-timeout" "args were: $(cat "$ARGS_FILE" 2>/dev/null)"
    fi

)

# --------------------------------------------------------------------------
echo ""
echo "----------------------------------------"
# grep -c prints 0 and exits 1 when there are no matches; the count is what
# matters here, so the exit status is deliberately ignored.
PASSED=$(grep -c '^P' "$RESULTS" 2>/dev/null)
FAILED=$(grep -c '^F' "$RESULTS" 2>/dev/null)
rm -f "$RESULTS"
echo "passed: $PASSED   failed: $FAILED"
if [[ "$FAILED" -gt 0 ]]; then
    exit 1
fi
exit 0
