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

function dump_service_diagnostics() {
    local service_dir="$1"
    local service_name="$2"
    local log_dir="${service_dir}/logs"

    echo "::group::${service_name} diagnostics"
    echo "[ci] service dir: ${service_dir}"
    echo "[ci] java processes:"
    ps -ef | grep -E "HugeGraph|hg-|java" | grep -v grep || true
    echo "[ci] listening tcp ports:"
    (ss -ltnp || netstat -ltnp || true) 2>&1

    if [ -d "${log_dir}" ]; then
        find "${log_dir}" -maxdepth 2 -type f | sort | while read -r log_file; do
            echo "--- tail -n 200 ${log_file} ---"
            tail -n 200 "${log_file}" || true
        done
    else
        echo "[ci] log dir not found: ${log_dir}"
    fi
    echo "::endgroup::"
}

function wait_for_tcp_port() {
    local service_name="$1"
    local host="$2"
    local port="$3"
    local pid_file="$4"
    local service_dir="$5"
    local timeout_seconds="${6:-90}"

    echo "[ci] waiting for ${service_name} at ${host}:${port}"
    for second in $(seq 1 "${timeout_seconds}"); do
        if bash -c "echo > /dev/tcp/${host}/${port}" >/dev/null 2>&1; then
            echo "[ci] ${service_name} is listening on ${host}:${port}"
            return 0
        fi

        if [ -f "${pid_file}" ]; then
            local pid
            pid="$(cat "${pid_file}")"
            if [ -n "${pid}" ] && ! kill -0 "${pid}" >/dev/null 2>&1; then
                echo "[ci] ${service_name} process ${pid} exited before readiness"
                dump_service_diagnostics "${service_dir}" "${service_name}"
                return 1
            fi
        fi

        if [ "$((second % 10))" -eq 0 ]; then
            echo "[ci] still waiting for ${service_name} (${second}s)"
        fi
        sleep 1
    done

    echo "[ci] timeout waiting for ${service_name} at ${host}:${port}"
    dump_service_diagnostics "${service_dir}" "${service_name}"
    return 1
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    command="$1"
    shift || true
    case "${command}" in
        dump)
            dump_service_diagnostics "$@"
            ;;
        wait)
            wait_for_tcp_port "$@"
            ;;
        *)
            echo "Usage: $0 dump SERVICE_DIR SERVICE_NAME"
            echo "       $0 wait SERVICE_NAME HOST PORT PID_FILE SERVICE_DIR [TIMEOUT_SECONDS]"
            exit 2
            ;;
    esac
fi
