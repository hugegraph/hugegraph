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
AUTH_INIT_STATE_FILE="auth_init_state"
GRAPH_CONF="./conf/graphs/hugegraph.properties"
REST_SERVER_CONF="./conf/rest-server.properties"
GREMLIN_SERVER_CONF="./conf/gremlin-server.yaml"
CONFIG_TOOL="${CONFIG_TOOL:-./bin/config-tool.sh}"

# The only in-tree HugeAuthenticator that bootstraps HugeGraph's built-in admin
# account. auth.authenticator accepts any implementation class, and a custom one
# (LDAP, OIDC, a plugin) manages its identities elsewhere, so the admin-account
# requirement below must not be applied to it.
BUILTIN_AUTHENTICATOR="org.apache.hugegraph.auth.StandardAuthenticator"

mkdir -p "${DOCKER_FOLDER}"

log() { echo "[hugegraph-server-entrypoint] $*"; }

# Property access goes through Commons Configuration, the parser HugeConfig
# uses. This keeps escaped keys, continuations, duplicate definitions and value
# serialization identical between the entrypoint and the server.
set_prop() {
    "${CONFIG_TOOL}" set "$3" "$1" "$2"
}

set_secret_prop() {
    printf '%s' "$2" | "${CONFIG_TOOL}" set-stdin "$3" "$1"
}

get_prop() {
    "${CONFIG_TOOL}" get "$2" "$1"
}

has_prop() {
    "${CONFIG_TOOL}" has "$2" "$1"
}

requires_local_admin() {
    "${CONFIG_TOOL}" requires-local-admin "${REST_SERVER_CONF}"
}

validate_skip() {
    "${CONFIG_TOOL}" validate-skip "${REST_SERVER_CONF}"
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

gremlin_auth_configured() {
    grep -qE '^authentication[[:space:]]*:' \
        "${GREMLIN_SERVER_CONF}" 2>/dev/null
}

# Print the single authenticator selected by the top-level authentication
# mapping. This intentionally accepts only the simple scalar form emitted by
# HugeGraph and Gremlin Server examples; aliases, substitutions, duplicates,
# or malformed mappings fail closed instead of risking different REST and
# Gremlin authentication providers.
gremlin_authenticator() {
    local blocks auth_block values
    blocks=$(grep -cE '^authentication[[:space:]]*:' \
        "${GREMLIN_SERVER_CONF}" 2>/dev/null || true)
    [[ "${blocks}" == "1" ]] || return 1

    auth_block=$(awk '
        /^authentication[[:space:]]*:/ { active=1 }
        active {
            if (seen && $0 ~ /^[^[:space:]#][^:]*[[:space:]]*:/) exit
            print
            seen=1
            if ($0 ~ /^[[:space:]]*}[[:space:]]*$/) exit
        }
    ' "${GREMLIN_SERVER_CONF}")
    values=$(printf '%s\n' "${auth_block}" | sed -nE \
        's/.*(^|[,{[:space:]])authenticator[[:space:]]*:[[:space:]]*([[:alnum:]_.$]+).*/\2/p')
    [[ "$(printf '%s\n' "${values}" | sed '/^$/d' | wc -l | tr -d ' ')" == "1" ]] || \
        return 1
    printf '%s\n' "${values}"
}

gremlin_auth_matches() {
    local configured
    configured=$(gremlin_authenticator) || return 1
    [[ "${configured}" == "$1" ]]
}

graph_auth_proxy_configured() {
    [[ "$(get_prop "gremlin.graph" "${GRAPH_CONF}")" == \
       "org.apache.hugegraph.auth.HugeFactoryAuthProxy" ]]
}

# Enables auth across all three configs. bin/enable-auth.sh does the same, but
# it appends unconditionally and guards itself only with conf-bak, which a
# mounted conf directory does not carry. Running it over a config that already
# enables auth would define the REST keys twice, which the parser rejects, and
# append a second `authentication:` block to the YAML. Skipping it instead would
# leave Gremlin unauthenticated and the graph outside the auth proxy whenever a
# mounted config sets only auth.authenticator. So run it for the untouched
# shipped config, and otherwise apply exactly the parts that are missing.
ensure_auth_enabled() {
    local authenticator class_pattern
    authenticator=$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")
    class_pattern='^[[:alpha:]_$][[:alnum:]_$]*(\.[[:alpha:]_$][[:alnum:]_$]*)*$'
    if [[ -n "${authenticator}" && ! "${authenticator}" =~ ${class_pattern} ]]; then
        log "ERROR: auth.authenticator must be a Java class name;" \
            "got '${authenticator}'"
        return 1
    fi

    if [[ -z "${authenticator}" ]] && ! gremlin_auth_configured && \
       ! graph_auth_proxy_configured; then
        ./bin/enable-auth.sh
        authenticator=$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")
    else
        log "auth is already configured in part; applying anything still missing"
    fi

    # enable-auth.sh is guarded by conf-bak and can return success without
    # changing restored configs. Enforce every postcondition after it runs.
    if [[ -z "${authenticator}" ]]; then
        authenticator="${BUILTIN_AUTHENTICATOR}"
        if ! set_prop "auth.authenticator" "${authenticator}" \
                      "${REST_SERVER_CONF}"; then
            log "ERROR: cannot write auth.authenticator to ${REST_SERVER_CONF}"
            return 1
        fi
    fi
    if [[ -z "$(get_prop "auth.graph_store" "${REST_SERVER_CONF}")" ]]; then
        if ! set_prop "auth.graph_store" "hugegraph" \
                      "${REST_SERVER_CONF}"; then
            log "ERROR: cannot write auth.graph_store to ${REST_SERVER_CONF}"
            return 1
        fi
    fi
    if gremlin_auth_configured; then
        if ! gremlin_auth_matches "${authenticator}"; then
            log "ERROR: Gremlin authenticator must match REST auth.authenticator '${authenticator}'"
            return 1
        fi
    else
        # A file whose last line has no newline would otherwise absorb the
        # first line of the block
        if [[ -s "${GREMLIN_SERVER_CONF}" && \
              -n "$(tail -c 1 "${GREMLIN_SERVER_CONF}")" ]]; then
            echo >> "${GREMLIN_SERVER_CONF}"
        fi
        # Kept in step with bin/enable-auth.sh, which owns this block, except
        # that Gremlin uses whichever authenticator the REST config names.
        cat >> "${GREMLIN_SERVER_CONF}" <<EOF
authentication: {
  authenticator: ${authenticator},
  authenticationHandler: org.apache.hugegraph.auth.WsAndHttpBasicAuthHandler,
  config: {tokens: conf/rest-server.properties}
}
EOF
    fi
    if ! graph_auth_proxy_configured; then
        if ! set_prop "gremlin.graph" \
                      "org.apache.hugegraph.auth.HugeFactoryAuthProxy" \
                      "${GRAPH_CONF}"; then
            log "ERROR: cannot write gremlin.graph to ${GRAPH_CONF}"
            return 1
        fi
    fi
}

require_configured_admin_password() {
    if ! has_prop "auth.admin_pa" "${REST_SERVER_CONF}" ||
       [[ -z "$(get_prop "auth.admin_pa" "${REST_SERVER_CONF}")" ]]; then
        log "ERROR: local built-in auth requires PASSWORD or an explicitly configured non-empty auth.admin_pa"
        return 1
    fi
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
    if ! set_prop "init_store.enabled" "${HG_SERVER_INIT_STORE_ENABLED}" \
                  "${REST_SERVER_CONF}"; then
        log "ERROR: cannot write init_store.enabled to ${REST_SERVER_CONF}"
        exit 1
    fi
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

# A mounted configuration can enable REST authentication without carrying the
# matching Gremlin handler or auth graph proxy. Complete all three configs for
# every configured authenticator, whether or not Docker supplied a PASSWORD.
AUTHENTICATOR=$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")
LEGACY_AUTH_CONFIG_ALIGNED=false
if [[ -n "${AUTHENTICATOR}" ]] &&
   [[ -d "./conf-bak" ]] &&
   [[ -n "$(get_prop "auth.graph_store" "${REST_SERVER_CONF}")" ]] &&
   gremlin_auth_matches "${AUTHENTICATOR}" &&
   graph_auth_proxy_configured; then
    LEGACY_AUTH_CONFIG_ALIGNED=true
fi
if [[ -z "${AUTHENTICATOR}" ]] &&
   { gremlin_auth_configured || graph_auth_proxy_configured; }; then
    log "ERROR: REST authentication is disabled while Gremlin or the graph" \
        "auth proxy remains enabled"
    exit 1
fi
if [[ -n "${PASSWORD:-}" || -n "${AUTHENTICATOR}" ]]; then
    ensure_auth_enabled
    AUTHENTICATOR=$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")
fi

LOCAL_BUILTIN_AUTH=false
if [[ -n "${AUTHENTICATOR}" ]] && requires_local_admin; then
    LOCAL_BUILTIN_AUTH=true
fi

AUTH_STATE=""
AUTH_INIT_REQUIRED=false
if [[ -n "${AUTHENTICATOR}" ]]; then
    AUTH_STATE=$(printf '%s\n%s\n%s' \
        "${AUTHENTICATOR}" \
        "$(get_prop "auth.remote_url" "${REST_SERVER_CONF}")" \
        "$(get_prop "auth.graph_store" "${REST_SERVER_CONF}")")
    STORED_AUTH_STATE=$(cat \
        "${DOCKER_FOLDER}/${AUTH_INIT_STATE_FILE}" 2>/dev/null || true)
    if [[ -f "${DOCKER_FOLDER}/${INIT_FLAG_FILE}" &&
          "${STORED_AUTH_STATE}" != "${AUTH_STATE}" ]]; then
        if [[ -z "${STORED_AUTH_STATE}" &&
              "${LEGACY_AUTH_CONFIG_ALIGNED}" == "true" ]]; then
            log "legacy authenticated volume detected; recording auth state without re-initialization"
            printf '%s\n' "${AUTH_STATE}" > \
                "${DOCKER_FOLDER}/${AUTH_INIT_STATE_FILE}"
        else
            AUTH_INIT_REQUIRED=true
        fi
    fi
fi

if [[ "${INIT_STORE_ENABLED:-true}" == "false" ]]; then
    log "init-store disabled; validating the no-op configuration"

    # Validate topology before writing a secret. The final init-store invocation
    # below repeats the Java gate after auth.admin_pa has been prepared and also
    # enforces that local built-in auth has an explicit non-empty password.
    validate_skip

    if [[ "${LOCAL_BUILTIN_AUTH}" == "true" ]]; then
        if [[ -n "${PASSWORD:-}" ]]; then
            log "enabling built-in auth, admin password applied via auth.admin_pa"
            # TODO: auth.admin_pa only applies when the admin account is first
            # created, so changing PASSWORD on a later restart keeps the old one.
            if ! chmod 600 "${REST_SERVER_CONF}"; then
                log "ERROR: cannot protect ${REST_SERVER_CONF} before writing auth.admin_pa"
                exit 1
            fi
            if ! set_secret_prop "auth.admin_pa" "${PASSWORD}" \
                                 "${REST_SERVER_CONF}"; then
                log "ERROR: cannot write auth.admin_pa to ${REST_SERVER_CONF}"
                exit 1
            fi
        elif ! require_configured_admin_password; then
            exit 1
        fi
    elif [[ -n "${PASSWORD:-}" ]]; then
        log "PASSWORD ignored: the configured authenticator does not use HugeGraph's local built-in admin"
    fi

    # The gate returns before backend or plugin registration, so this performs
    # validation only.
    ./bin/init-store.sh --validate-only

    # Still wait: the server needs the storage side reachable at startup even
    # though nothing is initialized here
    wait_storage
    # No init flag is written here: nothing was initialized, so a later run
    # with init-store enabled must still perform the real initialization.
elif [[ ! -f "${DOCKER_FOLDER}/${INIT_FLAG_FILE}" ||
        "${AUTH_INIT_REQUIRED}" == "true" ]]; then
    if [[ -f "${DOCKER_FOLDER}/${INIT_FLAG_FILE}" ]]; then
        log "authentication configuration changed; running init-store once"
    fi
    wait_storage

    if [[ -z "${PASSWORD:-}" ]]; then
        if [[ "${LOCAL_BUILTIN_AUTH}" == "true" ]]; then
            if ! require_configured_admin_password; then
                exit 1
            fi
            log "init hugegraph with configured auth.admin_pa"
            ./bin/init-store.sh --use-configured-admin-password
        elif [[ -n "${AUTHENTICATOR}" ]]; then
            log "init hugegraph with external authentication"
            ./bin/init-store.sh
        else
            log "init hugegraph with non-auth mode"
            ./bin/init-store.sh
        fi
    elif [[ "${LOCAL_BUILTIN_AUTH}" == "true" ]]; then
        log "init hugegraph with auth mode"
        printf '%s\n' "${PASSWORD}" | ./bin/init-store.sh
    else
        log "PASSWORD ignored: the configured authenticator does not use HugeGraph's local built-in admin"
        ./bin/init-store.sh
    fi
    # This flag tracks only that init has run. The branch above explicitly
    # handles later auth-mode changes; other HG_SERVER_* changes do not re-init.
    # It also does not survive a Kubernetes pod restart, which is why
    # init_store.enabled exists rather than a flag file.
    touch "${DOCKER_FOLDER}/${INIT_FLAG_FILE}"
    if [[ -n "${AUTH_STATE}" ]]; then
        printf '%s\n' "${AUTH_STATE}" > \
            "${DOCKER_FOLDER}/${AUTH_INIT_STATE_FILE}"
    fi
else
    log "HugeGraph initialization already done. Skipping re-init..."
fi

./bin/start-hugegraph.sh -j "${JAVA_OPTS:-}" -t 120

# Post-startup cluster stabilization check (hstore only — rocksdb has no partitions)
ACTUAL_BACKEND=$(get_prop "backend" "${GRAPH_CONF}")
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
