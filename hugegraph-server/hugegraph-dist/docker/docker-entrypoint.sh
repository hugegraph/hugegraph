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

# Sets a property to exactly one canonical `key=value` line. Existing
# definitions are matched on any separator a properties file allows (`=`, `:`
# or whitespace) and collapsed into that single line, because leaving a second
# definition behind would make the parser expose the key as a list and a scalar
# read of it would then fail. Comment lines are left alone. Matching is literal,
# so no regex escaping of the key or value is needed.
set_prop() {
    local key="$1" val="$2" file="$3"

    SET_PROP_KEY="$key" SET_PROP_VAL="$val" awk '
        BEGIN { key = ENVIRON["SET_PROP_KEY"]; val = ENVIRON["SET_PROP_VAL"] }
        {
            line = $0
            probe = line
            sub(/^[[:space:]]+/, "", probe)
            if (index(probe, key) == 1) {
                rest = substr(probe, length(key) + 1)
                if (rest ~ /^[[:space:]]*[=:]/ || rest ~ /^[[:space:]]+/) {
                    if (!done) { print key "=" val; done = 1 }
                    next
                }
            }
            print line
        }
        END { if (!done) print key "=" val }
    ' "${file}" > "${file}.tmp" && mv "${file}.tmp" "${file}"
}

# Escapes a value for Java-properties serialization. Backslashes must be
# doubled or the parser consumes them, so a password like `abc\def` would
# otherwise be read back as `abcdef`; a leading space would be stripped.
props_escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/^ /\\ /'
}

# Echoes the value of a property, or nothing when the key or the file is
# absent, so callers apply their own default. Accepts the `=`, `:` and
# whitespace separators that properties files allow. On duplicate keys the last
# one wins, matching how the properties parser reads the same file.
get_prop() {
    local key="$1" file="$2"
    local esc_key

    [[ -f "${file}" ]] || return 0
    esc_key=$(printf '%s' "$key" | sed -e 's/[][(){}.^$*+?|\\/]/\\&/g')
    # '#' delimits the s command because the pattern itself contains '|'
    sed -rn "s#^[[:space:]]*${esc_key}([[:space:]]*[=:]|[[:space:]]+)[[:space:]]*(.*)\$#\\2#p" \
        "${file}" | tail -n 1 | tr -d '[:space:]'
}

# Canonicalizes a boolean the way the server does. HugeConfig parses these
# options through commons-configuration2 PropertyConverter.toBoolean, i.e.
# BooleanUtils, which is case-insensitive and accepts y/t/on/yes/true and
# n/f/no/off/false. The shell must agree with it, or the two layers can
# disagree about whether to skip: `FALSE` once meant "skip" to Java and "run"
# to this script. Unrecognized values fail here, as they do in the server.
to_bool() {
    case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
        y|t|on|yes|true)   echo "true" ;;
        n|f|no|off|false)  echo "false" ;;
        *)                 return 1 ;;
    esac
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
if [[ -n "${HG_SERVER_INIT_STORE_ENABLED:-}" ]]; then
    # Canonicalize before writing, so the property file only ever holds `true`
    # or `false` and cannot be read differently by the shell and the server
    if ! HG_SERVER_INIT_STORE_ENABLED=$(to_bool "${HG_SERVER_INIT_STORE_ENABLED}"); then
        log "ERROR: HG_SERVER_INIT_STORE_ENABLED must be a boolean, got '${HG_SERVER_INIT_STORE_ENABLED}'"
        exit 1
    fi
    set_prop "init_store.enabled" "${HG_SERVER_INIT_STORE_ENABLED}" "${REST_SERVER_CONF}"
fi

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
if [[ -n "${INIT_STORE_ENABLED}" ]]; then
    if ! INIT_STORE_ENABLED=$(to_bool "${INIT_STORE_ENABLED}"); then
        log "ERROR: init_store.enabled in ${REST_SERVER_CONF} must be a boolean," \
            "got '${INIT_STORE_ENABLED}'"
        exit 1
    fi
fi
if [[ "${INIT_STORE_ENABLED:-true}" == "false" ]]; then
    log "init-store disabled, skipping local backend/admin init"

    # With init-store skipped, nothing creates the built-in admin account
    # unless the server takes the PD metadata path, which it only does when
    # `usePD=true`. Enabling auth without that combination starts a server
    # that enforces authentication while no account exists, so refuse it here
    # rather than fail every request later.
    AUTH_REQUESTED=""
    [[ -n "${PASSWORD:-}" ]] && AUTH_REQUESTED=1
    [[ -n "$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")" ]] && AUTH_REQUESTED=1
    if [[ -n "${AUTH_REQUESTED}" ]]; then
        USE_PD=$(to_bool "$(get_prop "usePD" "${REST_SERVER_CONF}")" 2>/dev/null || echo "false")
        if [[ "${USE_PD}" != "true" ]]; then
            log "ERROR: auth is enabled and init_store.enabled=false, but usePD is not true."
            log "ERROR: With init-store skipped the admin account is only created on the PD"
            log "ERROR: metadata path, so this combination would start a server that nobody"
            log "ERROR: can authenticate against."
            log "ERROR: Set usePD=true in ${REST_SERVER_CONF}, or leave init-store enabled"
            log "ERROR: so that it can create the admin account locally."
            exit 1
        fi
    fi

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
        set_prop "auth.admin_pa" "$(props_escape "${PASSWORD}")" "${REST_SERVER_CONF}"
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
