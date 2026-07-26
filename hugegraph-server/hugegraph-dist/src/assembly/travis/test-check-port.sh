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
# test-check-port.sh — Unit tests for check_port() in util.sh
#
# Strategy: each test runs check_port in a subshell that overrides
#   command_available() to control which probe branch is taken, and
#   overrides the tool functions (ss, netstat, timeout) to control
#   what they return — no real network connections needed.
#
# check_port calls `exit 1` when the port is in use, so the subshell
# exits 1; it returns normally (exit 0) when the port is free.
#
# Usage: ./test-check-port.sh [path-to-hugegraph-static-dir]
#   path-to-hugegraph-static-dir: directory containing bin/util.sh
#   Defaults to current directory.
#   In CI: $TRAVIS_DIR/test-check-port.sh hugegraph-server/hugegraph-dist/src/assembly/static

set -uo pipefail
# -u: fail on undefined variables (catches typos in test assertions)
# -o pipefail: pipeline exit status is the last non-zero component
# shellcheck disable=SC1090,SC1091  # UTIL_SH / PD_UTIL_SH sourced dynamically at runtime

STATIC_DIR="${1:-$(pwd)}"
UTIL_SH="$STATIC_DIR/bin/util.sh"

REPO_ROOT="$(cd "$(dirname "$0")/../../../../.." && pwd)"
PD_UTIL_SH="$REPO_ROOT/hugegraph-pd/hg-pd-dist/src/assembly/static/bin/util.sh"
STORE_UTIL_SH="$REPO_ROOT/hugegraph-store/hg-store-dist/src/assembly/static/bin/util.sh"

PASS=0
FAIL=0
ERRORS=()

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

pass() { echo -e "${GREEN}  PASS${NC} $1"; PASS=$((PASS + 1)); }
fail() { echo -e "${RED}  FAIL${NC} $1"; ERRORS+=("$1"); FAIL=$((FAIL + 1)); }
section() { echo ""; echo "── $1 ──"; }

# timeout mock used by ss/netstat test cases.  It passes getent through so
# normalize_addr can canonicalise numeric IPv6, and pretends every other
# probe (the /dev/tcp fallback) failed, so free-port assertions are not
# influenced by real network state.
timeout() {
    if [[ "${2:-}" == "getent" ]]; then
        shift
        "$@" 2>/dev/null
    else
        return 1
    fi
}

echo ""
echo "check_port() unit test suite"
echo "util.sh: $UTIL_SH"
echo ""

if [[ ! -f "$UTIL_SH" ]]; then
    echo -e "${RED}ERROR:${NC} $UTIL_SH not found."
    echo "       Pass the HugeGraph static assembly dir as \$1"
    exit 1
fi

# ── ss branch ─────────────────────────────────────────────────────────────────

section "ss branch — IPv4"

(
    # shellcheck source=/dev/null
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*"; }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss: IPv4 port occupied → exit 1" \
    || fail "ss: IPv4 port occupied → expected exit 1, got 0"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 0.0.0.0:9090 0.0.0.0:*"; }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 0 ]] \
    && pass "ss: IPv4 port free → exit 0" \
    || fail "ss: IPv4 port free → expected exit 0, got 1"

section "ss branch — IPv6 URL with scheme (http://[::1]:8080)"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 [::]:8080 [::]:*"; }
    check_port "http://[::1]:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss: http://[::1]:8080 occupied → exit 1" \
    || fail "ss: http://[::1]:8080 occupied → expected exit 1, got 0"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 [::]:9090 [::]:*"; }
    check_port "http://[::1]:8080"
)
[[ $? -eq 0 ]] \
    && pass "ss: http://[::1]:8080 free → exit 0" \
    || fail "ss: http://[::1]:8080 free → expected exit 0, got 1"

section "ss branch — IPv6 URL without scheme ([::1]:8080)"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 [::]:8080 [::]:*"; }
    check_port "[::1]:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss: [::1]:8080 (no scheme) occupied → exit 1" \
    || fail "ss: [::1]:8080 (no scheme) occupied → expected exit 1, got 0"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 [::]:9090 [::]:*"; }
    check_port "[::1]:8080"
)
[[ $? -eq 0 ]] \
    && pass "ss: [::1]:8080 (no scheme) free → exit 0" \
    || fail "ss: [::1]:8080 (no scheme) free → expected exit 0, got 1"

section "ss branch — wildcard 0.0.0.0"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*"; }
    check_port "http://0.0.0.0:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss: 0.0.0.0:8080 occupied → exit 1" \
    || fail "ss: 0.0.0.0:8080 occupied → expected exit 1, got 0"

section "ss branch — wildcard ::"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 [::]:8080 [::]:*"; }
    check_port "http://[::]:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss: [::]:8080 occupied → exit 1" \
    || fail "ss: [::]:8080 occupied → expected exit 1, got 0"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 [::]:9090 [::]:*"; }
    check_port "http://[::]:8080"
)
[[ $? -eq 0 ]] \
    && pass "ss: [::]:8080 free → exit 0" \
    || fail "ss: [::]:8080 free → expected exit 0, got 1"

# ── netstat branch ────────────────────────────────────────────────────────────

section "netstat branch — Linux format (-ltn), occupied"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "netstat" || "$1" == "timeout" ]]; }
    netstat() { echo "tcp 0 0 0.0.0.0:8080 0.0.0.0:* LISTEN"; }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 1 ]] \
    && pass "netstat -ltn: port 8080 occupied → exit 1" \
    || fail "netstat -ltn: port 8080 occupied → expected exit 1, got 0"

section "netstat branch — Linux format (-ltn), free"

(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "netstat" || "$1" == "timeout" ]]; }
    netstat() { echo "tcp 0 0 0.0.0.0:9090 0.0.0.0:* LISTEN"; }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 0 ]] \
    && pass "netstat -ltn: port 8080 free → exit 0" \
    || fail "netstat -ltn: port 8080 free → expected exit 0, got 1"

section "netstat branch — BSD/macOS fallback (-an), occupied"

# Simulate netstat that produces no output for -ltn (Linux flag unsupported)
# but outputs BSD-format lines for -an
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "netstat" || "$1" == "timeout" ]]; }
    netstat() {
        if [[ "$1" == "-ltn" ]]; then
            return 1  # flag not supported on BSD
        fi
        echo "tcp4 0 0 *.8080 *.* LISTEN"
    }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 1 ]] \
    && pass "netstat -an BSD: port 8080 occupied → exit 1" \
    || fail "netstat -an BSD: port 8080 occupied → expected exit 1, got 0"

section "netstat branch — IP octet false-positive guard"

# Port 80 check; netstat output contains 192.168.80.1:443
# The .80 in the IP address must NOT match port 80
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "netstat" || "$1" == "timeout" ]]; }
    netstat() { echo "tcp 0 0 192.168.80.1:443 0.0.0.0:* LISTEN"; }
    check_port "http://127.0.0.1:80"
)
[[ $? -eq 0 ]] \
    && pass "netstat: IP octet .80 does not false-positive for port 80 → exit 0" \
    || fail "netstat: IP octet .80 false-positived for port 80 → expected exit 0, got 1"

section "ss branch — host dot-escaping guard"

# Host 127.0.0.1 must be matched literally: a listener whose address merely
# matches the pattern with '.' as a regex wildcard (127a0b0c1) must NOT count
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 127a0b0c1:8080 0.0.0.0:*"; }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 0 ]] \
    && pass "ss: unescaped-dot lookalike 127a0b0c1 does not false-positive → exit 0" \
    || fail "ss: unescaped-dot lookalike 127a0b0c1 false-positived → expected exit 0, got 1"

# ── /dev/tcp fallback branch ──────────────────────────────────────────────────

section "/dev/tcp fallback — timeout available, port occupied"

# timeout exits 0 → connection succeeded → port in use
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "timeout" ]]; }
    timeout() {
        # Assert correct invocation: timeout 1 bash -c SCRIPT _ HOST PORT
        [[ "$1" == "1" && "$2" == "bash" && "$3" == "-c" && "$5" == "_" ]] \
            || { echo "timeout mock: unexpected argv: $*"; return 2; }
        return 0
    }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 1 ]] \
    && pass "/dev/tcp+timeout: connection succeeded (exit 0) → port occupied → exit 1" \
    || fail "/dev/tcp+timeout: connection succeeded → expected exit 1, got 0"

section "/dev/tcp fallback — timeout available, port free"

# timeout exits 1 → connection refused → port free
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "timeout" ]]; }
    timeout() {
        [[ "$1" == "1" && "$2" == "bash" && "$3" == "-c" && "$5" == "_" ]] \
            || { echo "timeout mock: unexpected argv: $*"; return 2; }
        return 1
    }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 0 ]] \
    && pass "/dev/tcp+timeout: connection refused (exit 1) → port free → exit 0" \
    || fail "/dev/tcp+timeout: connection refused → expected exit 0, got 1"

section "/dev/tcp fallback — real loopback (ephemeral port)"

# Start Python server on ephemeral port 0, capture actual port from child stdout
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "timeout" ]]; }
    # Mock timeout: on hosts without timeout (macOS), run the probe directly.
    # The probe args are: timeout 1 bash -c SCRIPT _ HOST PORT
    timeout() {
        [[ "$1" == "1" && "$2" == "bash" && "$3" == "-c" && "$5" == "_" ]] \
            || { echo "timeout mock: unexpected argv: $*" >&2; return 2; }
        # Run the probe with a 2-second hard deadline via background + watchdog
        bash -c "$4" "$5" "$6" "$7" 2>/dev/null &
        local probe_pid=$!
        (sleep 2; kill -9 "$probe_pid" 2>/dev/null) &
        local watchdog_pid=$!
        wait "$probe_pid" 2>/dev/null
        local rc=$?
        kill -9 "$watchdog_pid" 2>/dev/null
        wait "$watchdog_pid" 2>/dev/null
        return $rc
    }
    # Use a temp file to capture the bound port from child
    port_file=$(mktemp)
    trap 'rm -f "$port_file"; [[ -n "${PY_PID:-}" ]] && { kill -9 "$PY_PID" 2>/dev/null; wait "$PY_PID" 2>/dev/null; }' EXIT
    
    # Start Python server that prints the bound port to stdout
    python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', 0))
s.listen(1)  # actually listen so /dev/tcp can connect
port = s.getsockname()[1]
print(port, flush=True)
import time
time.sleep(30)  # keep server alive
" > "$port_file" 2>/dev/null &
    PY_PID=$!
    # Poll for port file up to ~4s (Python cold-start can exceed 0.5s on busy CI)
    bound_port=""
    for _ in $(seq 1 40); do
        bound_port=$(head -1 "$port_file" 2>/dev/null)
        [[ -n "$bound_port" ]] && break
        kill -0 $PY_PID 2>/dev/null || break
        sleep 0.1
    done
    if [[ -z "$bound_port" || ! "$bound_port" =~ ^[0-9]+$ ]]; then
        echo "SKIP: failed to get bound port"
        kill -9 $PY_PID 2>/dev/null || true
        exit 77
    fi
    # Verify child is alive
    if ! kill -0 $PY_PID 2>/dev/null; then
        echo "SKIP: Python child died"
        exit 77
    fi
    check_port "http://127.0.0.1:$bound_port"
)
_rc=$?
if [[ $_rc -eq 77 ]]; then
    echo "  SKIP /dev/tcp real ephemeral port (setup failed)"
elif [[ $_rc -eq 1 ]]; then
    pass "/dev/tcp real ephemeral port → detects occupation → exit 1"
else
    fail "/dev/tcp real ephemeral port → expected exit 1, got $_rc"
fi

section "/dev/tcp fallback — no-timeout watchdog kills stuck probe"

# run_with_deadline "sleep 5" 2 should return in ~2s, not ~5s.
# Proves the watchdog actually kills a stuck child, not just that the code path is taken.
(
    source "$UTIL_SH"
    start=$(date +%s)
    run_with_deadline 'sleep 5' 2
    rc=$?
    elapsed=$(($(date +%s) - start))
    # Must complete well before the 5s sleep (2s deadline + overhead) and return non-zero (killed)
    if [[ $rc -ne 0 && $elapsed -le 4 ]]; then
        exit 0
    else
        echo "run_with_deadline: rc=$rc elapsed=${elapsed}s (expected rc≠0, ≤4s)" >&2
        exit 1
    fi
)
[[ $? -eq 0 ]] \
    && pass "/dev/tcp no-timeout watchdog: sleep 5 killed in ≤4s" \
    || fail "/dev/tcp no-timeout watchdog: watchdog did not kill in time"

# ── host/port conflict semantics ──────────────────────────────────────────────

section "Host/port conflict semantics"

# Configured host 192.168.1.50, but only 127.0.0.1 is listening -> free
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "LISTEN 0 128 127.0.0.1:8080 0.0.0.0:*"; return 0; }
    check_port "http://192.168.1.50:8080"
)
[[ $? -eq 0 ]] \
    && pass "specific IP configured vs loopback listener → free → exit 0" \
    || fail "specific IP configured vs loopback listener → expected free, got occupied"

# Configured host 192.168.1.50, 0.0.0.0 is listening -> occupied
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*"; return 0; }
    check_port "http://192.168.1.50:8080"
)
[[ $? -eq 1 ]] \
    && pass "specific IP configured vs wildcard listener → occupied → exit 1" \
    || fail "specific IP configured vs wildcard listener → expected occupied, got free"

# Specific IPv4 host should not conflict with IPv6 wildcard listener
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "LISTEN 0 128 [::]:8080 [::]:*"; return 0; }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 0 ]] \
    && pass "specific IPv4 host vs IPv6 wildcard listener → free → exit 0" \
    || fail "specific IPv4 host vs IPv6 wildcard listener → expected free, got occupied"

# Specific IPv6 host should not conflict with IPv4 wildcard listener
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*"; return 0; }
    check_port "http://[::1]:8080"
)
[[ $? -eq 0 ]] \
    && pass "specific IPv6 host vs IPv4 wildcard listener → free → exit 0" \
    || fail "specific IPv6 host vs IPv4 wildcard listener → expected free, got occupied"

# ── tool failure fallthrough ──────────────────────────────────────────────────

section "Tool failure fallthrough"

# ss fails, netstat succeeds and finds occupied port
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "netstat" || "$1" == "timeout" ]]; }
    ss() { return 1; } # ss execution fails
    netstat() {
        if [[ "$1" == "-ltn" ]]; then echo "tcp 0 0 0.0.0.0:8080 0.0.0.0:* LISTEN"; return 0; fi
        return 1
    }
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss exits 1 → fallthrough to netstat → occupied → exit 1" \
    || fail "ss exits 1 → fallthrough to netstat → expected occupied, got free"

# both ss and netstat fail, /dev/tcp timeout path must still detect occupation
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "netstat" || "$1" == "timeout" ]]; }
    ss() { return 1; }
    netstat() { return 1; }
    timeout() { return 0; }  # /dev/tcp connect succeeds
    check_port "http://127.0.0.1:8080"
)
[[ $? -eq 1 ]] \
    && pass "ss/netstat both fail → /dev/tcp timeout path occupied → exit 1" \
    || fail "ss/netstat both fail → expected /dev/tcp fallback occupation, got free"

# ── hostname and URL normalization ────────────────────────────────────────────

section "Hostname collision detection"

# localhost should resolve to numeric addresses before ss matching
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "getent" || "$1" == "timeout" ]]; }
    timeout() { shift; "$@" 2>/dev/null; }  # pass-through for mocked getent
    getent() {
        if [[ "$1" == "hosts" || "$1" == "ahosts" ]] && [[ "$2" == "localhost" ]]; then
            echo "127.0.0.1"
            return 0
        fi
        return 1
    }
    ss() { echo "tcp LISTEN 0 128 127.0.0.1:8080 0.0.0.0:*"; }
    check_port "http://localhost:8080"
)
[[ $? -eq 1 ]] \
    && pass "hostname localhost resolves to 127.0.0.1 and detects occupation → exit 1" \
    || fail "hostname localhost occupation was not detected via resolved address"

# unresolved hostnames should skip ss/netstat text matching and use /dev/tcp fallback
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "getent" || "$1" == "timeout" ]]; }
    getent() { return 2; }  # resolution fails
    ss() { echo "tcp LISTEN 0 128 127.0.0.1:8080 0.0.0.0:*"; return 0; }
    timeout() { return 0; } # fallback connect succeeds
    check_port "http://unresolved.localdomain:8080"
)
[[ $? -eq 1 ]] \
    && pass "unresolved hostname falls through to /dev/tcp fallback and detects occupation" \
    || fail "unresolved hostname should use /dev/tcp fallback"

section "URL normalization and IPv6 hextet guard"

# check_port should trim URL whitespace before parsing host/port
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    ss() { echo "tcp LISTEN 0 128 127.0.0.1:8080 0.0.0.0:*"; }
    check_port "   http://127.0.0.1:8080   "
)
[[ $? -eq 1 ]] \
    && pass "leading/trailing URL whitespace is normalized → occupied detected" \
    || fail "URL whitespace normalization failed to detect occupied port"

# Ensure ":8080" inside an IPv6 hextet does not match listener port 8080
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    timeout() {
        if [[ "$2" == "getent" ]]; then
            shift
            "$@" 2>/dev/null
        else
            return 1
        fi
    }
    ss() { echo "tcp LISTEN 0 128 [2001:db8:8080::1]:9090 [::]:*"; }
    check_port "http://[::1]:8080"
)
[[ $? -eq 0 ]] \
    && pass "IPv6 hextet containing 8080 does not false-positive port 8080" \
    || fail "IPv6 hextet false-positive: expected free, got occupied"

# ── IPv6 address canonicalisation ─────────────────────────────────────────────

section "IPv6 address canonicalisation"

# getent mock that normalises the two spellings of loopback to ::1
getent_canonical_loopback() {
    if [[ "$1" == "ahosts" ]] && [[ "$2" == "::1" || "$2" == "0:0:0:0:0:0:0:1" ]]; then
        echo "::1"
        return 0
    fi
    return 2
}

# Host ::1, listener expanded 0:0:0:0:0:0:0:1
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    getent() { getent_canonical_loopback "$@"; }
    ss() { echo "tcp LISTEN 0 128 [0:0:0:0:0:0:0:1]:8080 [::]:*"; }
    check_port "http://[::1]:8080"
)
[[ $? -eq 1 ]] \
    && pass "IPv6 host [::1] matches expanded listener [0:0:0:0:0:0:0:1]:8080" \
    || fail "IPv6 compressed vs expanded spelling did not match"

# Host expanded 0:0:0:0:0:0:0:1, listener ::1 (mixed case)
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    getent() { getent_canonical_loopback "$@"; }
    ss() { echo "tcp LISTEN 0 128 [::1]:8080 [::]:*"; }
    check_port "http://[0:0:0:0:0:0:0:1]:8080"
)
[[ $? -eq 1 ]] \
    && pass "IPv6 expanded host matches compressed listener" \
    || fail "IPv6 expanded vs compressed spelling did not match"

# Host ::1, listener expanded on a different port → free
(
    source "$UTIL_SH"
    command_available() { [[ "$1" == "ss" || "$1" == "timeout" ]]; }
    timeout() {
        if [[ "$2" == "getent" ]]; then
            shift
            "$@" 2>/dev/null
        else
            return 1
        fi
    }
    getent() { getent_canonical_loopback "$@"; }
    ss() { echo "tcp LISTEN 0 128 [0:0:0:0:0:0:0:1]:9090 [::]:*"; }
    check_port "http://[::1]:8080"
)
[[ $? -eq 0 ]] \
    && pass "IPv6 canonicalisation does not false-positive on different port" \
    || fail "IPv6 canonicalisation matched the wrong port"

# ── edge cases ────────────────────────────────────────────────────────────────

section "Edge cases"

# Empty URL → port string is empty → return 0 immediately
(
    source "$UTIL_SH"
    command_available() { false; }
    check_port ""
)
[[ $? -eq 0 ]] \
    && pass "empty URL → exit 0 (graceful)" \
    || fail "empty URL → expected exit 0, got 1"

# Port out of range (> 65535) → return 0 immediately
(
    source "$UTIL_SH"
    command_available() { false; }
    check_port "http://127.0.0.1:99999"
)
[[ $? -eq 0 ]] \
    && pass "port 99999 (out of range) → exit 0 (graceful)" \
    || fail "port 99999 (out of range) → expected exit 0, got 1"

# Port 0 (invalid) → return 0 immediately
(
    source "$UTIL_SH"
    command_available() { false; }
    check_port "http://127.0.0.1:0"
)
[[ $? -eq 0 ]] \
    && pass "port 0 (invalid) → exit 0 (graceful)" \
    || fail "port 0 (invalid) → expected exit 0, got 1"

# Non-numeric port → return 0 immediately
(
    source "$UTIL_SH"
    command_available() { false; }
    check_port "127.0.0.1:abc"
)
[[ $? -eq 0 ]] \
    && pass "non-numeric port → exit 0 (graceful)" \
    || fail "non-numeric port → expected exit 0, got 1"

# ── download() fallback ───────────────────────────────────────────────────────

section "download() atomic curl path (pd)"

(
    if [[ ! -f "$PD_UTIL_SH" ]]; then
        exit 77
    fi

    source "$PD_UTIL_SH"

    OUTDIR="$(mktemp -d)/outdir"
    trap 'rm -rf "$(dirname "$OUTDIR")"' EXIT

    command_available() {
        if [[ "$1" == "wget" ]]; then return 1; fi
        if [[ "$1" == "curl" ]]; then return 0; fi
        command -v "$1" >/dev/null 2>&1
    }

    MKDIR_CALLED=0
    mkdir() {
        if [[ "$1" == "-p" && "$2" == "$OUTDIR" ]]; then
            MKDIR_CALLED=1
            command mkdir -p "$OUTDIR"
            return 0
        fi
        echo "mkdir mock called with unexpected args: $*"
        return 1
    }

    CURL_CALLED=0
    curl() {
        # Expected: curl -fL -o <tmp> -- <url>
        if [[ "$1" == "-fL" && "$2" == "-o" && \
              "$4" == "--" && \
              "$5" == "https://example.com/some/path/file.tar.gz" ]]; then
            CURL_CALLED=1
            # The temp name must be hidden and contain the PID.
            if [[ "$3" != "$OUTDIR/.file.tar.gz.tmp."* ]]; then
                echo "curl mock: unexpected output path: $3"
                return 1
            fi
            touch "$3"
            return 0
        fi
        echo "curl mock called with unexpected args: $*"
        return 1
    }

    download "$OUTDIR" "https://example.com/some/path/file.tar.gz" >/dev/null 2>&1
    RC=$?

    if [[ $RC -eq 0 && $CURL_CALLED -eq 1 && $MKDIR_CALLED -eq 1 && \
          -f "$OUTDIR/file.tar.gz" ]]; then
        exit 0
    else
        echo "RC=$RC CURL_CALLED=$CURL_CALLED MKDIR_CALLED=$MKDIR_CALLED"
        ls -la "$OUTDIR" 2>&1 || true
        exit 1
    fi
)
_rc=$?
if [[ $_rc -eq 77 ]]; then
    echo "  SKIP download() atomic curl path: pd util.sh not found"
elif [[ $_rc -eq 0 ]]; then
    pass "download() (PD) writes to temp and renames on success"
else
    fail "download() (PD) atomic curl path failed"
fi

section "download() atomic curl path (store)"

(
    if [[ ! -f "$STORE_UTIL_SH" ]]; then
        exit 77
    fi

    source "$STORE_UTIL_SH"

    OUTDIR="$(mktemp -d)/outdir"
    trap 'rm -rf "$(dirname "$OUTDIR")"' EXIT

    command_available() {
        if [[ "$1" == "wget" ]]; then return 1; fi
        if [[ "$1" == "curl" ]]; then return 0; fi
        command -v "$1" >/dev/null 2>&1
    }

    MKDIR_CALLED=0
    mkdir() {
        if [[ "$1" == "-p" && "$2" == "$OUTDIR" ]]; then
            MKDIR_CALLED=1
            command mkdir -p "$OUTDIR"
            return 0
        fi
        echo "mkdir mock called with unexpected args: $*"
        return 1
    }

    CURL_CALLED=0
    curl() {
        if [[ "$1" == "-fL" && "$2" == "-o" && \
              "$4" == "--" && \
              "$5" == "https://example.com/some/path/file.tar.gz" ]]; then
            CURL_CALLED=1
            if [[ "$3" != "$OUTDIR/.file.tar.gz.tmp."* ]]; then
                echo "curl mock: unexpected output path: $3"
                return 1
            fi
            touch "$3"
            return 0
        fi
        echo "curl mock called with unexpected args: $*"
        return 1
    }

    download "$OUTDIR" "https://example.com/some/path/file.tar.gz" >/dev/null 2>&1
    RC=$?

    if [[ $RC -eq 0 && $CURL_CALLED -eq 1 && $MKDIR_CALLED -eq 1 && \
          -f "$OUTDIR/file.tar.gz" ]]; then
        exit 0
    else
        echo "RC=$RC CURL_CALLED=$CURL_CALLED MKDIR_CALLED=$MKDIR_CALLED"
        ls -la "$OUTDIR" 2>&1 || true
        exit 1
    fi
)
_rc=$?
if [[ $_rc -eq 77 ]]; then
    echo "  SKIP download() atomic curl path: store util.sh not found"
elif [[ $_rc -eq 0 ]]; then
    pass "download() (Store) writes to temp and renames on success"
else
    fail "download() (Store) atomic curl path failed"
fi

section "download() atomic wget path (pd)"

(
    if [[ ! -f "$PD_UTIL_SH" ]]; then
        exit 77
    fi

    source "$PD_UTIL_SH"

    OUTDIR="$(mktemp -d)/outdir"
    trap 'rm -rf "$(dirname "$OUTDIR")"' EXIT

    command_available() {
        if [[ "$1" == "curl" ]]; then return 1; fi
        if [[ "$1" == "wget" ]]; then return 0; fi
        command -v "$1" >/dev/null 2>&1
    }

    MKDIR_CALLED=0
    mkdir() {
        if [[ "$1" == "-p" && "$2" == "$OUTDIR" ]]; then
            MKDIR_CALLED=1
            command mkdir -p "$OUTDIR"
            return 0
        fi
        echo "mkdir mock called with unexpected args: $*"
        return 1
    }

    WGET_CALLED=0
    wget() {
        if [[ "$1" == "--help" ]]; then
            # wget --help runs in a pipeline subshell, so side-effect counters
            # cannot be observed from the parent.  Only the stdout matters here.
            echo "--show-progress"
            return 0
        fi
        # Expected: wget -q --show-progress -O <tmp> -- <url>
        if [[ "$1" == "-q" && "$2" == "--show-progress" && "$3" == "-O" && \
              "$5" == "--" && \
              "$6" == "https://example.com/some/path/file.tar.gz" ]]; then
            WGET_CALLED=1
            if [[ "$4" != "$OUTDIR/.file.tar.gz.tmp."* ]]; then
                echo "wget mock: unexpected output path: $4"
                return 1
            fi
            touch "$4"
            return 0
        fi
        echo "wget mock called with unexpected args: $*"
        return 1
    }

    download "$OUTDIR" "https://example.com/some/path/file.tar.gz" >/dev/null 2>&1
    RC=$?

    if [[ $RC -eq 0 && $WGET_CALLED -eq 1 && $MKDIR_CALLED -eq 1 && \
          -f "$OUTDIR/file.tar.gz" ]]; then
        exit 0
    else
        echo "RC=$RC WGET_CALLED=$WGET_CALLED MKDIR_CALLED=$MKDIR_CALLED"
        ls -la "$OUTDIR" 2>&1 || true
        exit 1
    fi
)
_rc=$?
if [[ $_rc -eq 77 ]]; then
    echo "  SKIP download() atomic wget path: pd util.sh not found"
elif [[ $_rc -eq 0 ]]; then
    pass "download() (PD) wget writes to temp and renames on success"
else
    fail "download() (PD) atomic wget path failed"
fi

section "download() atomic wget path (store)"

(
    if [[ ! -f "$STORE_UTIL_SH" ]]; then
        exit 77
    fi

    source "$STORE_UTIL_SH"

    OUTDIR="$(mktemp -d)/outdir"
    trap 'rm -rf "$(dirname "$OUTDIR")"' EXIT

    command_available() {
        if [[ "$1" == "curl" ]]; then return 1; fi
        if [[ "$1" == "wget" ]]; then return 0; fi
        command -v "$1" >/dev/null 2>&1
    }

    MKDIR_CALLED=0
    mkdir() {
        if [[ "$1" == "-p" && "$2" == "$OUTDIR" ]]; then
            MKDIR_CALLED=1
            command mkdir -p "$OUTDIR"
            return 0
        fi
        echo "mkdir mock called with unexpected args: $*"
        return 1
    }

    WGET_CALLED=0
    wget() {
        if [[ "$1" == "--help" ]]; then
            # wget --help runs in a pipeline subshell, so side-effect counters
            # cannot be observed from the parent.  Only the stdout matters here.
            echo "--show-progress"
            return 0
        fi
        # Expected: wget -q --show-progress -O <tmp> -- <url>
        if [[ "$1" == "-q" && "$2" == "--show-progress" && "$3" == "-O" && \
              "$5" == "--" && \
              "$6" == "https://example.com/some/path/file.tar.gz" ]]; then
            WGET_CALLED=1
            if [[ "$4" != "$OUTDIR/.file.tar.gz.tmp."* ]]; then
                echo "wget mock: unexpected output path: $4"
                return 1
            fi
            touch "$4"
            return 0
        fi
        echo "wget mock called with unexpected args: $*"
        return 1
    }

    download "$OUTDIR" "https://example.com/some/path/file.tar.gz" >/dev/null 2>&1
    RC=$?

    if [[ $RC -eq 0 && $WGET_CALLED -eq 1 && $MKDIR_CALLED -eq 1 && \
          -f "$OUTDIR/file.tar.gz" ]]; then
        exit 0
    else
        echo "RC=$RC WGET_CALLED=$WGET_CALLED MKDIR_CALLED=$MKDIR_CALLED"
        ls -la "$OUTDIR" 2>&1 || true
        exit 1
    fi
)
_rc=$?
if [[ $_rc -eq 77 ]]; then
    echo "  SKIP download() atomic wget path: store util.sh not found"
elif [[ $_rc -eq 0 ]]; then
    pass "download() (Store) wget writes to temp and renames on success"
else
    fail "download() (Store) atomic wget path failed"
fi

section "download() curl failure cleanup (pd)"

# curl failure must clean up the partial file and never leave a poisoned artifact
(
    if [[ ! -f "$PD_UTIL_SH" ]]; then exit 77; fi
    source "$PD_UTIL_SH"

    OUTDIR=$(mktemp -d)
    trap 'rm -rf "$OUTDIR"' EXIT

    command_available() {
        if [[ "$1" == "wget" ]]; then return 1; fi
        if [[ "$1" == "curl" ]]; then return 0; fi
        command -v "$1" >/dev/null 2>&1
    }
    mkdir() { command mkdir -p "$2"; return 0; }
    PARTIAL=""
    curl() {
        if [[ "$1" == "-fL" && "$2" == "-o" && \
              "$4" == "--" && \
              "$5" == "https://example.com/some/path/file.tar.gz" ]]; then
            PARTIAL="$3"
            # Simulate a partial/corrupted transfer: write something then fail.
            echo "partial" > "$PARTIAL"
            return 1
        fi
        return 1
    }
    download "$OUTDIR" "https://example.com/some/path/file.tar.gz" >/dev/null 2>&1
    rc=$?

    if [[ $rc -ne 0 && ! -f "$OUTDIR/file.tar.gz" && \
          ( -z "$PARTIAL" || ! -f "$PARTIAL" ) ]]; then
        exit 0
    else
        echo "rc=$rc PARTIAL=$PARTIAL DEST=$([[ -f $OUTDIR/file.tar.gz ]] && echo exists || echo missing)"
        ls -la "$OUTDIR" 2>&1 || true
        exit 1
    fi
)
_rc=$?
if [[ $_rc -eq 77 ]]; then
    echo "  SKIP download() curl failure cleanup: pd util.sh not found"
elif [[ $_rc -eq 0 ]]; then
    pass "download() curl failure cleans temp and does not poison destination"
else
    fail "download() curl failure did not clean partial file"
fi

# ── summary ───────────────────────────────────────────────────────────────────

echo ""
echo "════════════════════════════════"
echo -e "  Results: ${GREEN}$PASS passed${NC}  ${RED}$FAIL failed${NC}"
echo "════════════════════════════════"

if [[ $FAIL -gt 0 ]]; then
    echo ""
    echo "Failed tests:"
    for err in ${ERRORS[@]+"${ERRORS[@]}"}; do
        echo -e "  ${RED}✗${NC} $err"
    done
fi

echo ""
[[ $FAIL -eq 0 ]] && exit 0 || exit 1
