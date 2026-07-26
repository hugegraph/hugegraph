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

DOCKER_FOLDER="./docker"
INIT_FLAG_FILE="init_complete"
GRAPH_CONF="./conf/graphs/hugegraph.properties"
REST_SERVER_CONF="./conf/rest-server.properties"

mkdir -p "${DOCKER_FOLDER}"

log() { echo "[hugegraph-server-entrypoint] $*"; }

set_prop() {
    local key="$1" val="$2" file="$3"
    local esc_key esc_val

    esc_key=$(printf '%s' "$key" | sed -e 's/[][(){}.^$*+?|\\/]/\\&/g')
    esc_val=$(printf '%s' "$val" | sed -e 's/[&|\\]/\\&/g')

    if grep -qE "^[[:space:]]*${esc_key}[[:space:]]*=" "${file}"; then
        sed -ri "s|^([[:space:]]*${esc_key}[[:space:]]*=).*|\\1${esc_val}|" "${file}"
    else
        printf '%s=%s\n' "$key" "$val" >> "${file}"
    fi
}

# Echoes the value of a property, or nothing when the key or the file is
# absent, so callers apply their own default. On duplicate keys the last one
# wins, matching how java.util.Properties reads the same file.
get_prop() {
    local key="$1" file="$2"
    local esc_key

    [[ -f "${file}" ]] || return 0
    esc_key=$(printf '%s' "$key" | sed -e 's/[][(){}.^$*+?|\\/]/\\&/g')
    sed -rn "s|^[[:space:]]*${esc_key}[[:space:]]*=[[:space:]]*(.*)$|\\1|p" \
        "${file}" | tail -n 1 | tr -d '[:space:]'
}

migrate_env() {
    local old_name="$1" new_name="$2"

    if [[ -n "${!old_name:-}" && -z "${!new_name:-}" ]]; then
        log "WARN: deprecated env '${old_name}' detected; mapping to '${new_name}'"
        export "${new_name}=${!old_name}"
    fi
}

migrate_env "BACKEND"  "HG_SERVER_BACKEND"
migrate_env "PD_PEERS" "HG_SERVER_PD_PEERS"

# ── Map env → properties file ─────────────────────────────────────────
[[ -n "${HG_SERVER_BACKEND:-}"  ]] && set_prop "backend"  "${HG_SERVER_BACKEND}"  "${GRAPH_CONF}"
[[ -n "${HG_SERVER_PD_PEERS:-}" ]] && set_prop "pd.peers" "${HG_SERVER_PD_PEERS}" "${GRAPH_CONF}"
[[ -n "${HG_SERVER_INIT_STORE_ENABLED:-}" ]] && set_prop "init_store.enabled" "${HG_SERVER_INIT_STORE_ENABLED}" "${REST_SERVER_CONF}"

# ── Build wait-storage env ─────────────────────────────────────────────
WAIT_ENV=()
[[ -n "${HG_SERVER_BACKEND:-}"  ]] && WAIT_ENV+=("hugegraph.backend=${HG_SERVER_BACKEND}")
[[ -n "${HG_SERVER_PD_PEERS:-}" ]] && WAIT_ENV+=("hugegraph.pd.peers=${HG_SERVER_PD_PEERS}")

wait_storage() {
    if (( ${#WAIT_ENV[@]} > 0 )); then
        env "${WAIT_ENV[@]}" ./bin/wait-storage.sh
    else
        ./bin/wait-storage.sh
    fi
}

# ── Init store (once) ─────────────────────────────────────────────────
# With `init_store.enabled=false` (distributed PD/HStore) init-store is a no-op:
# storage owns the metadata and the admin account is created on server startup
# from `auth.admin_pa`. A requested PASSWORD is therefore written to that
# property rather than piped into init-store.sh, where it would be read and
# discarded without creating the account.
#
# The value is read back from the config file rather than from the env var, so
# that a rest-server.properties mounted with the property already set behaves
# the same as `HG_SERVER_INIT_STORE_ENABLED` (the env mapping above has already
# been applied, so env still wins).
INIT_STORE_ENABLED=$(get_prop "init_store.enabled" "${REST_SERVER_CONF}")
if [[ "${INIT_STORE_ENABLED:-true}" == "false" ]]; then
    log "init-store disabled, skipping local backend/admin init"
    # Still wait: the server needs the storage side reachable at startup even
    # though nothing is initialized here
    wait_storage

    if [[ -n "${PASSWORD:-}" ]]; then
        log "enabling auth mode, admin password applied via auth.admin_pa"
        ./bin/enable-auth.sh
        # TODO: auth.admin_pa only applies when the admin account is first
        # created, so changing PASSWORD on a later restart silently keeps the
        # old one. It also leaves the password at rest in rest-server.properties,
        # unlike the enabled path where it only travels over stdin.
        set_prop "auth.admin_pa" "${PASSWORD}" "${REST_SERVER_CONF}"
    fi
    # No init flag is written here: nothing was initialized, so a later run
    # with init-store enabled must still perform the real initialization.
elif [[ ! -f "${DOCKER_FOLDER}/${INIT_FLAG_FILE}" ]]; then
    wait_storage

    if [[ -z "${PASSWORD:-}" ]]; then
        log "init hugegraph with non-auth mode"
        ./bin/init-store.sh
    else
        log "init hugegraph with auth mode"
        ./bin/enable-auth.sh
        echo "${PASSWORD}" | ./bin/init-store.sh
    fi
    # TODO: this flag only tracks "init has run", not what it ran with. On a
    # persisted volume it survives env changes, so flipping HG_SERVER_* on a
    # later start will not re-init. It also does not survive a Kubernetes pod
    # restart, which is why init_store.enabled exists rather than a flag file.
    touch "${DOCKER_FOLDER}/${INIT_FLAG_FILE}"
else
    log "HugeGraph initialization already done. Skipping re-init..."
fi

./bin/start-hugegraph.sh -j "${JAVA_OPTS:-}" -t 120

# Post-startup cluster stabilization check (hstore only — rocksdb has no partitions)
ACTUAL_BACKEND=$(grep -E '^[[:space:]]*backend[[:space:]]*=' "${GRAPH_CONF}" | head -n 1 | sed 's/.*=//' | tr -d '[:space:]' || true)
if [[ "${ACTUAL_BACKEND}" == "hstore" ]]; then
    STORE_REST="${STORE_REST:-store:8520}"
    export STORE_REST
    ./bin/wait-partition.sh || log "WARN: partitions not assigned yet"
fi

PID=$(cat ./bin/pid 2>/dev/null || true)
if [[ -n "$PID" ]]; then
    trap 'kill -TERM "$PID" 2>/dev/null; while kill -0 "$PID" 2>/dev/null; do sleep 1; done; exit 0' TERM INT
    tail --pid="$PID" -f /dev/null
    exit 1
fi
