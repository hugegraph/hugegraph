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

function command_available() {
    local cmd=$1
    if command -v "$cmd" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

function configure_riscv64_libatomic() {
    if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "riscv64" ]]; then
        return 0
    fi

    if [[ "${LD_PRELOAD:-}" == *"libatomic.so.1"* ]]; then
        return 0
    fi

    local libatomic=""
    if command_available "ldconfig"; then
        libatomic=$(ldconfig -p 2>/dev/null | \
                    awk '/libatomic\.so\.1 .*=>/ && !path {path=$NF}
                         END {if (path) print path}')
    fi

    if [[ -z "$libatomic" ]]; then
        local candidate
        for candidate in /lib/riscv64-linux-gnu/libatomic.so.1 \
                         /usr/lib/riscv64-linux-gnu/libatomic.so.1 \
                         /lib64/lp64d/libatomic.so.1 \
                         /usr/lib64/lp64d/libatomic.so.1 \
                         /lib64/libatomic.so.1 \
                         /usr/lib64/libatomic.so.1; do
            if [[ -r "$candidate" ]]; then
                libatomic="$candidate"
                break
            fi
        done
    fi

    if [[ -z "$libatomic" ]]; then
        echo "RISC-V RocksDB requires libatomic.so.1; install libatomic1" >&2
        return 1
    fi

    export LD_PRELOAD="${LD_PRELOAD:+${LD_PRELOAD}:}${libatomic}"
}

# read a property from .properties file
function read_property() {
    # file path
    file_name=$1
    # replace "." to "\."
    property_name=$(echo "$2" | sed 's/\./\\\./g')
    cat "$file_name" | sed -n -e "s/^[ ]*//g;/^#/d;s/^$property_name=//p" | tail -1
}

function write_property() {
    local file=$1
    local key=$2
    local value=$3

    local os=$(uname)
    case $os in
        # Note: in mac os should use sed -i '' "xxx" to replace string,
        # otherwise prompt 'command c expects \ followed by text'.
        # See http://www.cnblogs.com/greedy-day/p/5952899.html
        Darwin) sed -i '' "s!$key=.*!$key=$value!g" "$file" ;;
        *) sed -i "s!$key=.*!$key=$value!g" "$file" ;;
    esac
}

function parse_yaml() {
    local file=$1
    local version=$2
    local module=$3

    cat "$file" | tr -d '\n {}'| awk -F',+|:' '''{
        pre="";
        for(i=1; i<=NF; ) {
            if(match($i, /version/)) {
                pre=$i;
                i+=1
            } else {
                result[pre"-"$i] = $(i+1);
                i+=2
            }
        }
    } END {for(e in result) {print e": "result[e]}}''' \
    | grep "$version-$module" | awk -F':' '{print $2}' | tr -d ' ' && echo
}

function process_num() {
    local num
    num=$(ps -ef | grep "$1" | grep -v grep | wc -l)
    # Return 0 when no process, 1 when one or more.  Using $num directly as an
    # exit code would truncate values > 255, so treat this as a boolean result.
    if (( num > 0 )); then
        return 1
    fi
    return 0
}

function process_id() {
    local pid
    pid=$(ps -ef | grep "$1" | grep -v grep | awk '{print $2}')
    echo "$pid"
    return 0
}

# Run a command with a hard deadline via background watchdog.
# Returns the command's exit code if it finishes in time.
# If the deadline expires, the command is killed (exit code reflects signal).
# Works without the timeout command — uses sleep + kill -9 pattern.
function run_with_deadline() {
    local cmd="$1"
    local deadline="$2"
    shift 2

    bash -c "$cmd" bash "$@" &
    local child_pid=$!
    (
        local sleep_pid=""
        cleanup_watchdog() {
            [[ -n "$sleep_pid" ]] && kill -9 "$sleep_pid" 2>/dev/null
        }
        # Kill any direct children of the target PID (e.g. a spawned sleep)
        # in case killing the wrapper left them orphaned.
        kill_children() {
            local child
            for child in $(pgrep -P "$1" 2>/dev/null); do
                kill -9 "$child" 2>/dev/null || true
            done
        }
        trap 'cleanup_watchdog' EXIT TERM
        sleep "$deadline" & sleep_pid=$!
        wait "$sleep_pid" 2>/dev/null
        if kill -0 "$child_pid" 2>/dev/null; then
            kill -9 "$child_pid" 2>/dev/null
            kill_children "$child_pid"
        fi
    ) 2>/dev/null &
    local watchdog_pid=$!

    wait "$child_pid" 2>/dev/null
    local rc=$?
    # Kill the watchdog with SIGTERM so its EXIT trap reaps the sleep child.
    kill -TERM "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
    return $rc
}


# Normalize an IP address to a canonical string for comparison.
# - IPv4 addresses are returned unchanged.
# - IPv6 addresses are stripped of brackets/zone scope, lowercased and
#   compressed to the canonical textual form (via getent ahosts).
# - IPv4-mapped IPv6 (::ffff:1.2.3.4) is collapsed to the IPv4 form.
# - Hostnames are returned unchanged; callers should resolve them first.
function normalize_addr() {
    local addr="$1"

    # Strip brackets
    if [[ "$addr" =~ ^\[.*\]$ ]]; then
        addr="${addr#\[}"
        addr="${addr%\]}"
    fi

    # Drop IPv6 zone scope (e.g. 127.0.0.53%lo, fe80::1%eth0)
    addr="${addr%%\%*}"

    # IPv4-mapped IPv6 -> IPv4 so a bound IPv4-mapped socket is compared
    # against an IPv4-configured address.
    if [[ "$addr" =~ ^::ffff:([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
        echo "${BASH_REMATCH[1]}"
        return
    fi

    # Plain IPv4
    if [[ "$addr" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "$addr"
        return
    fi

    # IPv6: ask glibc for the canonical compressed form
    if [[ "$addr" =~ ^[0-9a-fA-F:]*:[0-9a-fA-F:]*$ ]]; then
        local norm
        if command_available "timeout"; then
            norm=$(timeout 2 getent ahosts "$addr" 2>/dev/null | awk '{print $1; exit}')
        else
            norm=$(getent ahosts "$addr" 2>/dev/null | awk '{print $1; exit}')
        fi
        if [[ -n "$norm" ]]; then
            echo "$norm"
            return
        fi
        # macOS/BSD fallback: python3's socket module can canonicalise
        # numeric IPv6 even when getent is unavailable.
        if command_available "python3"; then
            norm=$(python3 -c 'import socket, sys; print(socket.inet_ntop(socket.AF_INET6, socket.inet_pton(socket.AF_INET6, sys.argv[1])))' "$addr" 2>/dev/null)
            if [[ -n "$norm" ]]; then
                echo "$norm"
                return
            fi
        fi
        # No canonicaliser available: at least normalise hex case so ss/netstat
        # lowercase output still matches an uppercase configured address.
        echo "$addr" | tr '[:upper:]' '[:lower:]'
        return
    fi

    # Fall back to the cleaned original
    echo "$addr"
}

# check whether the REST server port is occupied
function check_port() {
    local url="$1"
    local host
    local port

    # Strip leading/trailing whitespace from URL (handles whitespace from ServerOptions)
    url="${url#"${url%%[![:space:]]*}"}"
    url="${url%"${url##*[![:space:]]}"}"

    # Extract authority: strip scheme and stop at the first /, ? or #.
    local authority
    authority="${url#*://}"
    authority="${authority%%[/?#]*}"

    # Extract host and port from authority.
    if [[ "$authority" =~ ^\[([^\]]*)\]:([0-9]+)$ ]]; then
        # IPv6 with port: [::1]:8080
        host="${BASH_REMATCH[1]}"
        port="${BASH_REMATCH[2]}"
    elif [[ "$authority" =~ ^\[([^\]]*)\]$ ]]; then
        # IPv6 without port: [::1]
        host="${BASH_REMATCH[1]}"
        port=""
    elif [[ "$authority" =~ :([0-9]+)$ ]]; then
        # IPv4 or hostname with port: 127.0.0.1:8080, localhost:8080
        port="${BASH_REMATCH[1]}"
        host="${authority%:*}"
    else
        # No explicit port in authority
        host="$authority"
        port=""
    fi

    # Handle default ports from scheme when no explicit port is given
    if [[ -z "$port" ]]; then
        if [[ "$url" == https://* ]]; then
            port="443"
        elif [[ "$url" == http://* ]]; then
            port="80"
        else
            return 0
        fi
    fi

    # Validate port as a decimal number
    if ! [[ "$port" =~ ^[0-9]+$ ]]; then
        return 0
    fi
    port=$((10#$port))
    if (( port < 1 || port > 65535 )); then
        return 0
    fi

    # Strip any leading/trailing whitespace from host
    host="${host#"${host%%[![:space:]]*}"}"
    host="${host%"${host##*[![:space:]]}"}"

    local norm_host
    norm_host=$(normalize_addr "$host")

    # Determine the address family of the configured host so we only treat
    # same-family wildcard listeners as conflicts (IPv4 vs IPv6 sockets are
    # separate unless explicitly dual-stacked).
    local host_family=""
    if [[ "$norm_host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        host_family="ipv4"
    elif [[ "$norm_host" =~ ^[0-9a-fA-F:]*:[0-9a-fA-F:]*$ ]]; then
        host_family="ipv6"
    else
        host_family="hostname"
    fi

    local in_use=0
    local port_checked=0

    # Wildcard binds are detected by looking for any listener on the port.
    # Specific hosts are matched against the local address; if the listener
    # is a same-family wildcard (0.0.0.0 / :: / *), that is a conflict too.
    local is_wildcard=0
    if [[ -z "$host" || "$host" == "0.0.0.0" || "$host" == "::" || "$host" == "*" ]]; then
        is_wildcard=1
    fi

    # Build the list of acceptable normalized addresses for a specific host.
    # Hostnames must be resolved to numeric IPs first because ss/netstat
    # output is always numeric.
    local candidate_addrs=""
    if [[ $is_wildcard -eq 0 ]]; then
        if [[ "$host_family" == "ipv4" || "$host_family" == "ipv6" ]]; then
            # Already numeric
            candidate_addrs="$norm_host"
        else
            # Hostname: resolve with deadline
            if command_available "getent" && command_available "timeout"; then
                candidate_addrs=$(timeout 2 getent ahosts "$host" 2>/dev/null | awk '{print $1}')
            elif command_available "dscacheutil" && command_available "timeout"; then
                candidate_addrs=$(timeout 2 dscacheutil -q host -a name "$host" 2>/dev/null \
                                  | awk '/ip_address:/{print $2}')
            fi
        fi
    fi

    # Helper: scan a line of listener-table output and return 0 if it matches
    # the configured host/port.  Sets 'matched_token' to 1 when it evaluates a
    # token that we can trust, so callers know whether "no match" is reliable.
    local out line listener_addr norm_listener token matched_token

    # Returns true if the listener address is a wildcard on the same family
    # as the configured host (or any family, if the host is a wildcard).
    _check_port_wildcard_conflicts() {
        local wl_addr="$1"
        if [[ $is_wildcard -eq 1 ]]; then
            return 0
        fi
        # BSD netstat prints *.<port> for an any-family wildcard.
        if [[ "$wl_addr" == "*" ]]; then
            return 0
        fi
        if [[ "$wl_addr" == "0.0.0.0" ]]; then
            [[ "$host_family" == "ipv4" ]] && return 0
            # A hostname that resolved to IPv4 can also bind an IPv4 wildcard
            if [[ "$host_family" == "hostname" ]]; then
                local addr
                while IFS= read -r addr; do
                    [[ -z "$addr" ]] && continue
                    if [[ "$addr" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
                        return 0
                    fi
                done <<< "$candidate_addrs"
            fi
        fi
        if [[ "$wl_addr" == "::" ]]; then
            [[ "$host_family" == "ipv6" ]] && return 0
            if [[ "$host_family" == "hostname" ]]; then
                local addr
                while IFS= read -r addr; do
                    [[ -z "$addr" ]] && continue
                    if [[ "$addr" =~ ^[0-9a-fA-F:]*:[0-9a-fA-F:]*$ ]]; then
                        return 0
                    fi
                done <<< "$candidate_addrs"
            fi
        fi
        return 1
    }

    _check_port_match_listener_line() {
        local out_line="$1"
        # Skip empty or whitespace-only lines before read -a to avoid an empty
        # array expansion under set -u on Bash 3.2.
        if [[ -z "${out_line//[[:space:]]/}" ]]; then
            return 1
        fi
        local -a tokens
        read -r -a tokens <<< "$out_line"
        for token in ${tokens[@]+"${tokens[@]}"}; do
            # We only care about the "local address:port" token.  For the
            # common tools this is the first token that ends with the
            # target port after ':' or '.' (peer addresses use :* on Linux
            # and *.* on BSD, so they never match a numeric port).
            if [[ "$token" =~ ^(.*):(${port})$ ]]; then
                listener_addr="${BASH_REMATCH[1]}"
            elif [[ "$token" =~ ^(.*)\.(${port})$ ]]; then
                listener_addr="${BASH_REMATCH[1]}"
            else
                continue
            fi

            # Wildcard host: any listener on this port is a conflict.
            if [[ $is_wildcard -eq 1 ]]; then
                matched_token=1
                return 0
            fi

            # No resolved candidates (hostname resolution failed/unsupported):
            # we cannot reliably compare against the numeric listener table.
            if [[ -z "$candidate_addrs" ]]; then
                continue
            fi

            matched_token=1
            norm_listener=$(normalize_addr "$listener_addr")

            # A same-family wildcard listener on this port conflicts with any
            # specific host of that family.
            if _check_port_wildcard_conflicts "$norm_listener"; then
                return 0
            fi

            local addr
            while IFS= read -r addr; do
                [[ -z "$addr" ]] && continue
                if [[ "$norm_listener" == "$(normalize_addr "$addr")" ]]; then
                    return 0
                fi
            done <<< "$candidate_addrs"
        done
        return 1
    }

    if command_available "ss"; then
        matched_token=0
        if out=$(ss -ltn 2>/dev/null); then
            while IFS= read -r line; do
                if _check_port_match_listener_line "$line"; then
                    in_use=1
                    break
                fi
            done <<< "$out"
            # ss -ltn succeeded.  We can trust a non-match when we have a
            # numeric host or resolved addresses (or a wildcard); otherwise
            # fall through to /dev/tcp for unresolved hostnames.
            if [[ $in_use -eq 0 && ( $is_wildcard -eq 1 || -n "$candidate_addrs" || $matched_token -eq 1 ) ]]; then
                port_checked=1
            fi
        fi
    fi

    if [[ $in_use -eq 0 && $port_checked -eq 0 ]] && command_available "netstat"; then
        matched_token=0
        if out=$(netstat -ltn 2>/dev/null) && echo "$out" | grep -qi "listen"; then
            while IFS= read -r line; do
                if _check_port_match_listener_line "$line"; then
                    in_use=1
                    break
                fi
            done <<< "$out"
            # netstat -ltn output is the complete Linux listener table.
            if [[ $in_use -eq 0 && ( $is_wildcard -eq 1 || -n "$candidate_addrs" || $matched_token -eq 1 ) ]]; then
                port_checked=1
            fi
        elif out=$(netstat -an 2>/dev/null) && [[ -n "$out" ]]; then
            local old_nocasematch
            old_nocasematch=$(shopt -p nocasematch 2>/dev/null || true)
            shopt -s nocasematch 2>/dev/null || true
            while IFS= read -r line; do
                if [[ "$line" == *listen* ]] && _check_port_match_listener_line "$line"; then
                    in_use=1
                    break
                fi
            done <<< "$out"
            eval "$old_nocasematch" 2>/dev/null || true
            # netstat -an output is the complete BSD listener table.
            if [[ $in_use -eq 0 && ( $is_wildcard -eq 1 || -n "$candidate_addrs" || $matched_token -eq 1 ) ]]; then
                port_checked=1
            fi
        fi
    fi

    if [[ $in_use -eq 0 && $port_checked -eq 0 ]]; then
        # Probe the actual configured endpoint(s) with a short deadline.
        local probe_addrs="$candidate_addrs"
        if [[ -z "$probe_addrs" ]]; then
            # Could not resolve (or wildcard with only loopback probe needed)
            if [[ $is_wildcard -eq 1 ]]; then
                probe_addrs="127.0.0.1 ::1"
            else
                probe_addrs="$host"
            fi
        fi

        local addr
        for addr in $probe_addrs; do
            # /dev/tcp needs unbracketed, normalized addresses
            addr=$(normalize_addr "$addr")
            [[ -z "$addr" ]] && continue
            if command_available "timeout"; then
                if timeout 1 bash -c ': >/dev/tcp/"$1"/"$2"' _ "$addr" "$port" 2>/dev/null; then
                    in_use=1
                    break
                fi
            else
                if run_with_deadline ': >/dev/tcp/"$1"/"$2" 2>/dev/null' 2 "$addr" "$port"; then
                    in_use=1
                    break
                fi
            fi
        done
    fi

    local _rc=0
    if [[ "$in_use" -eq 1 ]]; then
        echo "The port $port has already been used"
        _rc=1
    fi
    unset -f _check_port_wildcard_conflicts _check_port_match_listener_line || true
    [[ $_rc -eq 1 ]] && exit 1
    return 0
}

function crontab_append() {
    local job="$1"
    crontab -l | grep -F "$job" >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        return 1
    fi
    (crontab -l ; echo "$job") | crontab -
}

function crontab_remove() {
    local job="$1"
    # check exist before remove
    crontab -l | grep -F "$job" >/dev/null 2>&1
    if [ $? -eq 1 ]; then
        return 0
    fi

    crontab -l | grep -Fv "$job"  | crontab -

    # Check exist after remove
    crontab -l | grep -F "$job" >/dev/null 2>&1
    if [ $? -eq 0 ]; then
        return 1
    else
        return 0
    fi
}

# wait_for_startup friendly_name host port timeout_s
function wait_for_startup() {
    local pid="$1"
    local server_name="$2"
    local server_url="$3"
    local timeout_s="$4"

    local now_s
    now_s=$(date '+%s')
    local stop_s=$((now_s + timeout_s))

    local status
    local error_file_name="startup_error.txt"

    echo -n "Connecting to $server_name ($server_url)"
    while [ "$now_s" -le "$stop_s" ]; do
        echo -n .
        process_status "$server_name" "$pid" >/dev/null
        if [ $? -eq 1 ]; then
            echo "Starting $server_name failed"
            if [ -e "$error_file_name" ]; then
                rm "$error_file_name"
            fi
            return 1
        fi

        status=$(curl -I -sS -k -w "%{http_code}" -o /dev/null "$server_url" 2> "$error_file_name")
        if [[ "$status" -eq 200 || "$status" -eq 401 ]]; then
            echo "OK"
            echo "Started [pid $pid]"
            if [ -e "$error_file_name" ]; then
                rm "$error_file_name"
            fi
            return 0
        fi
        sleep 2
        now_s=$(date '+%s')
    done

    echo ""
    cat "$error_file_name"
    rm "$error_file_name"
    echo "The operation timed out(${timeout_s}s) when attempting to connect to $server_url" >&2
    return 1
}

function free_memory() {
    local free=""
    local os=$(uname)
    if [ "$os" == "Linux" ]; then
        local mem_free=$(cat /proc/meminfo | grep -w "MemFree" | awk '{print $2}')
        local mem_buffer=$(cat /proc/meminfo | grep -w "Buffers" | awk '{print $2}')
        local mem_cached=$(cat /proc/meminfo | grep -w "Cached" | awk '{print $2}')
        if [[ "$mem_free" == "" || "$mem_buffer" == "" || "$mem_cached" == "" ]]; then
            echo "Failed to get free memory"
            exit 1
        fi
        free=$(expr "$mem_free" + "$mem_buffer" + "$mem_cached")
        free=$(expr "$free" / 1024)
    elif [ "$os" == "Darwin" ]; then
        local pages_free pages_inactive
        pages_free=$(vm_stat | awk '/Pages free/{print $0}' | awk -F'[:.]+' '{print $2}' | tr -d " ")
        pages_inactive=$(vm_stat | awk '/Pages inactive/{print $0}' | awk -F'[:.]+' '{print $2}' | tr -d " ")
        if [[ -z "$pages_free" || -z "$pages_inactive" ]]; then
            echo "Failed to get free memory"
            exit 1
        fi
        local pages_available
        pages_available=$(expr "$pages_free" + "$pages_inactive")
        free=$(expr "$pages_available" \* 4096 / 1024 / 1024)
    else
        echo "Unsupported operating system $os"
        exit 1
    fi
    echo "$free"
}

function calc_xmx() {
    local min_mem=$1
    local max_mem=$2
    # Get machine available memory
    local free=$(free_memory)
    local half_free=$((free/2))

    local xmx=$min_mem
    if [[ "$free" -lt "$min_mem" ]]; then
        exit 1
    elif [[ "$half_free" -ge "$max_mem" ]]; then
        xmx=$max_mem
    elif [[ "$half_free" -lt "$min_mem" ]]; then
        xmx=$min_mem
    else
        xmx=$half_free
    fi
    echo $xmx
}

function remove_with_prompt() {
    local path=$1
    local tips=""

    if [ -d "$path" ]; then
        tips="Remove directory '$path' and all sub files [y/n]?"
    elif [ -f "$path" ]; then
        tips="Remove file '$path' [y/n]?"
    else
        return 0
    fi

    read -p "$tips " yn
    case $yn in
        [Yy]* ) rm -rf "$path";;
        * ) ;;
    esac
}

function ensure_path_writable() {
    local path=$1
    # Ensure input path exist
    if [ ! -d "${path}" ]; then
        mkdir -p "${path}"
    fi
    # Check for write permission
    if [ ! -w "${path}" ]; then
        echo "No write permission on directory ${path}"
        exit 1
    fi
}

function get_ip() {
    local os=$(uname)
    local loopback="127.0.0.1"
    local ip=""
    case $os in
        Linux)
            if command_available "ifconfig"; then
                ip=$(ifconfig | grep 'inet addr:' | grep -v "$loopback" | cut -d: -f2 | awk '{ print $1}')
            elif command_available "ip"; then
                ip=$(ip addr | grep 'state UP' -A2 | tail -n1 | awk '{print $2}' | awk -F"/" '{print $1}')
            else
                ip=$loopback
            fi
            ;;
        FreeBSD|OpenBSD|Darwin)
            if command_available "ifconfig"; then
                ip=$(ifconfig | grep -E 'inet.[0-9]' | grep -v "$loopback" | awk '{ print $2}')
            else
                ip=$loopback
            fi
            ;;
        SunOS)
            if command_available "ifconfig"; then
                ip=$(ifconfig -a | grep inet | grep -v "$loopback" | awk '{ print $2} ')
            else
                ip=$loopback
            fi
            ;;
        *) ip=$loopback;;
    esac
    [[ -z "$ip" ]] && ip=$loopback
    echo "$ip"
}

function download() {
    local path=$1
    local download_url=$2

    if [ ! -d "$path" ]; then
        mkdir -p "$path" || {
            echo "Failed to create directory: $path"
            exit 1
        }
    fi

    # Strip query/fragment so the on-disk name matches the server-side artifact.
    local filename
    filename=$(basename "${download_url%%[?#]*}")
    local tmp="${path}/.${filename}.tmp.$$"
    local dest="${path}/${filename}"

    if command_available "curl"; then
        # -o must appear before -- so it is parsed as an option, not an extra URL.
        if curl -fL -o "$tmp" -- "${download_url}"; then
            mv -f -- "$tmp" "$dest"
        else
            rm -f -- "$tmp"
            return 1
        fi
    elif command_available "wget"; then
        local -a progress_opt=()
        if wget --help 2>&1 | grep -q -- '--show-progress'; then
            progress_opt=(-q --show-progress)
        fi
        if wget ${progress_opt[@]+"${progress_opt[@]}"} -O "$tmp" -- "${download_url}"; then
            mv -f -- "$tmp" "$dest"
        else
            rm -f -- "$tmp"
            return 1
        fi
    else
        echo "Required curl or wget but they are unavailable"
        exit 1
    fi
}

function ensure_package_exist() {
    local path=$1
    local dir=$2
    local tar=$3
    local link=$4

    if [ ! -d "${path}"/"${dir}" ]; then
        if [ ! -f "${path}"/"${tar}" ]; then
            echo "Downloading the compressed package '${tar}'"
            download "${path}" "${link}"
            if [ $? -ne 0 ]; then
                echo "Failed to download, please ensure the network is available and link is valid"
                exit 1
            fi
            echo "[OK] Finished download"
        fi
        echo "Unzip the compressed package '$tar'"
        tar zxvf "${path}"/"${tar}" -C "${path}" >/dev/null 2>&1
        if [ $? -ne 0 ]; then
            echo "Failed to unzip, please check the compressed package"
            exit 1
        fi
        echo "[OK] Finished unzip"
    fi
}

###########################################################################

function wait_for_shutdown() {
    local process_name="$1"
    local pid="$2"
    local timeout_s="$3"

    local now_s=$(date '+%s')
    local stop_s=$((now_s + timeout_s))

    echo -n "Killing $process_name(pid $pid)" >&2
    while [ "$now_s" -le $stop_s ]; do
        echo -n .
        process_status "$process_name" "$pid" >/dev/null
        if [ $? -eq 1 ]; then
            echo "OK"
            return 0
        fi
        sleep 2
        now_s=$(date '+%s')
    done
    echo "$process_name shutdown timeout(exceeded $timeout_s seconds)" >&2
    return 1
}

function process_status() {
    local process_name="$1"
    local pid="$2"

    ps -p "$pid"
    if [ $? -eq 0 ]; then
        echo "$process_name is running with pid $pid"
        return 0
    else
        echo "The process $process_name does not exist"
        return 1
    fi
}

function kill_process() {
    local process_name="$1"
    local pid="$2"

    if [ -z "$pid" ]; then
        echo "The process $pid does not exist"
        return 0
    fi

    case "$(uname)" in
        CYGWIN*) taskkill /F /PID "$pid" ;;
        *)       kill "$pid" ;;
    esac
}

function kill_process_and_wait() {
    local process_name="$1"
    local pid="$2"
    local timeout_s="$3"

    kill_process "$process_name" "$pid"
    wait_for_shutdown "$process_name" "$pid" "$timeout_s"
}

function exit_with_usage_help(){
    echo "USAGE: $0 [-d true|false] [-g g1] [-m true|false] [-p true|false] [-s true|false] [-j java_options] [-t timeout] [-y true|false]"
    exit 1
}
