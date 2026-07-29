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

# The only in-tree HugeAuthenticator that bootstraps HugeGraph's built-in admin
# account. auth.authenticator accepts any implementation class, and a custom one
# (LDAP, OIDC, a plugin) manages its identities elsewhere, so the admin-account
# requirement below must not be applied to it.
BUILTIN_AUTHENTICATOR="org.apache.hugegraph.auth.StandardAuthenticator"

mkdir -p "${DOCKER_FOLDER}"

log() { echo "[hugegraph-server-entrypoint] $*"; }

# Sets a property to exactly one canonical `key=value` line. Existing
# definitions are matched on any separator a properties file allows (`=`, `:`
# or whitespace) and collapsed into that single line, because leaving a second
# definition behind would make the parser expose the key as a list and a scalar
# read of it would then fail. Comment lines are left alone. Matching is literal,
# so no regex escaping of the key or value is needed.
set_prop() {
    local key="$1" val="$2" file="$3" tmp
    tmp="${file}.tmp.$$"

    # The scratch file holds auth.admin_pa, so keep it off the process umask
    ( umask 077; : > "${tmp}" )

    if ! SET_PROP_KEY="$key" SET_PROP_VAL="$val" awk '
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
    ' "${file}" > "${tmp}"; then
        rm -f "${tmp}"
        return 1
    fi

    # Truncate and rewrite in place rather than rename: a single-file bind
    # mount cannot be replaced by rename, and a rename would also discard the
    # original ownership and mode, which matters where auth.admin_pa is written
    if ! cat "${tmp}" > "${file}"; then
        rm -f "${tmp}"
        return 1
    fi
    rm -f "${tmp}"
}

count_prop() {
    local key="$1" file="$2"

    [[ -f "${file}" ]] || { echo 0; return; }
    SET_PROP_KEY="$key" awk '
        BEGIN { key = ENVIRON["SET_PROP_KEY"] }
        {
            probe = $0
            sub(/^[[:space:]]+/, "", probe)
            if (index(probe, key) == 1) {
                rest = substr(probe, length(key) + 1)
                if (rest ~ /^[[:space:]]*[=:]/ || rest ~ /^[[:space:]]+/) {
                    count++
                }
            }
        }
        END { print count + 0 }
    ' "${file}"
}

# Drops duplicate definitions while leaving a single valid definition untouched.
# Avoiding a needless rewrite lets complete read-only mounted configs start.
canonicalize_prop() {
    local key="$1" file="$2" count cur
    count=$(count_prop "${key}" "${file}")
    if (( count > 1 )); then
        cur=$(get_prop "${key}" "${file}")
        set_prop "${key}" "${cur}" "${file}"
    fi
}

# Escapes a UTF-8 value for Java-properties serialization. Encoding every
# UTF-16 code unit as a Unicode escape keeps separators, leading whitespace,
# backslashes and embedded control characters out of the physical property
# line while the Java parser reconstructs the exact original string.
props_escape() {
    printf '%s' "$1" | iconv -f UTF-8 -t UTF-16BE | \
        od -An -v -t x1 | awk '
            {
                for (i = 1; i <= NF; i++) {
                    if (high == "") {
                        high = $i
                    } else {
                        printf "\\u%s%s", high, $i
                        high = ""
                    }
                }
            }
            END { if (high != "") exit 1 }
        '
}

# Echoes the value of a property, or nothing when the key or the file is
# absent, so callers apply their own default. Accepts the `=`, `:` and
# whitespace separators that properties files allow. On duplicate keys the last
# one wins, matching how the properties parser reads the same file. Only
# surrounding whitespace is trimmed, as the parser does; whitespace inside a
# value is part of the value and deleting it would corrupt one.
get_prop() {
    local key="$1" file="$2"
    local esc_key

    [[ -f "${file}" ]] || return 0
    esc_key=$(printf '%s' "$key" | sed -e 's/[][(){}.^$*+?|\\/]/\\&/g')
    # '#' delimits the s command because the pattern itself contains '|'.
    # '-E' rather than '-r': both GNU and BSD sed accept it
    sed -En "s#^[[:space:]]*${esc_key}([[:space:]]*[=:]|[[:space:]]+)[[:space:]]*(.*)\$#\\2#p" \
        "${file}" | tail -n 1 | sed -e 's/[[:space:]]*$//'
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
    grep -qE '^[[:space:]]*authentication:' "${GREMLIN_SERVER_CONF}" 2>/dev/null
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
        log "ERROR: auth.authenticator must be an unescaped Java class name;" \
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
        set_prop "auth.authenticator" "${authenticator}" \
                 "${REST_SERVER_CONF}"
    fi
    if [[ -z "$(get_prop "auth.graph_store" "${REST_SERVER_CONF}")" ]]; then
        set_prop "auth.graph_store" "hugegraph" "${REST_SERVER_CONF}"
    fi
    if ! gremlin_auth_configured; then
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
        set_prop "gremlin.graph" \
                 "org.apache.hugegraph.auth.HugeFactoryAuthProxy" \
                 "${GRAPH_CONF}"
    fi

    # enable-auth.sh appends rather than replaces, so collapse whatever it left
    # behind into one definition per key
    canonicalize_prop "auth.authenticator" "${REST_SERVER_CONF}"
    canonicalize_prop "auth.graph_store" "${REST_SERVER_CONF}"
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

# A mounted configuration can enable REST authentication without carrying the
# matching Gremlin handler or auth graph proxy. Complete all three configs for
# every configured authenticator, whether or not Docker supplied a PASSWORD.
AUTHENTICATOR=$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")
if [[ -n "${PASSWORD:-}" || -n "${AUTHENTICATOR}" ]]; then
    ensure_auth_enabled
    AUTHENTICATOR=$(get_prop "auth.authenticator" "${REST_SERVER_CONF}")
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
        AUTH_INIT_REQUIRED=true
    fi
fi

if [[ "${INIT_STORE_ENABLED:-true}" == "false" ]]; then
    log "init-store disabled; validating the no-op configuration"

    # Let InitStore make the type-aware decision about whether this effective
    # authenticator needs the built-in admin and whether the configured auth
    # graph can read the PD-created account. The gate returns before backend or
    # plugin registration, so this invocation performs validation only.
    ./bin/init-store.sh

    # Still wait: the server needs the storage side reachable at startup even
    # though nothing is initialized here
    wait_storage

    if [[ -n "${PASSWORD:-}" ]]; then
        log "enabling auth mode, admin password applied via auth.admin_pa"
        # TODO: auth.admin_pa only applies when the admin account is first
        # created, so changing PASSWORD on a later restart keeps the old one.
        if ! ESCAPED_PASSWORD=$(props_escape "${PASSWORD}"); then
            log "ERROR: PASSWORD must be valid UTF-8"
            exit 1
        fi
        if ! chmod 600 "${REST_SERVER_CONF}"; then
            log "ERROR: cannot protect ${REST_SERVER_CONF} before writing auth.admin_pa"
            exit 1
        fi
        set_prop "auth.admin_pa" "${ESCAPED_PASSWORD}" "${REST_SERVER_CONF}"
    fi
    # No init flag is written here: nothing was initialized, so a later run
    # with init-store enabled must still perform the real initialization.
elif [[ ! -f "${DOCKER_FOLDER}/${INIT_FLAG_FILE}" ||
        "${AUTH_INIT_REQUIRED}" == "true" ]]; then
    if [[ -f "${DOCKER_FOLDER}/${INIT_FLAG_FILE}" ]]; then
        log "authentication configuration changed; running init-store once"
    fi
    wait_storage

    if [[ -z "${PASSWORD:-}" ]]; then
        if [[ -n "${AUTHENTICATOR}" ]]; then
            log "init hugegraph with configured auth.admin_pa"
            ./bin/init-store.sh --use-configured-admin-password
        else
            log "init hugegraph with non-auth mode"
            ./bin/init-store.sh
        fi
    else
        log "init hugegraph with auth mode"
        printf '%s\n' "${PASSWORD}" | ./bin/init-store.sh
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
