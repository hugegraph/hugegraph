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

if [ "$(uname -s)" != "Linux" ]; then
    echo "[install-rocksdb] Skip native preload on non-Linux platform: $(uname -s)"
    return 0 2>/dev/null || exit 0
fi

ORIG_SHELL_FLAGS="$-"
ORIG_PIPEFAIL="$(set -o | awk '$1 == "pipefail" { print $2 }')"
ORIG_ERR_TRAP="$(trap -p ERR)"
ORIG_EXIT_TRAP="$(trap -p EXIT)"
# Save original IFS to avoid leaking into parent shell when sourced
ORIG_IFS="${IFS}"
set -Eeuo pipefail
IFS=$'\n\t'

install_rocksdb_restore_state() {
    local exit_status=$?

    # Prevent recursive execution while restoring the caller's EXIT trap.
    trap - EXIT
    IFS="$ORIG_IFS"
    if [ -n "$ORIG_ERR_TRAP" ]; then
        eval "$ORIG_ERR_TRAP"
    else
        trap - ERR
    fi
    if [ -n "$ORIG_EXIT_TRAP" ]; then
        eval "$ORIG_EXIT_TRAP"
    else
        trap - EXIT
    fi
    case "$ORIG_SHELL_FLAGS" in *e*) set -e ;; *) set +e ;; esac
    case "$ORIG_SHELL_FLAGS" in *u*) set -u ;; *) set +u ;; esac
    case "$ORIG_SHELL_FLAGS" in *E*) set -E ;; *) set +E ;; esac
    if [ "$ORIG_PIPEFAIL" = "on" ]; then
        set -o pipefail
    else
        set +o pipefail
    fi
    return "$exit_status"
}
trap install_rocksdb_restore_state EXIT

# Unified error capture for easy positioning
trap 'echo "[install-rocksdb] error at line ${LINENO}: ${BASH_COMMAND}" >&2' ERR

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
COMPONENT="${1:-server}"
SERVER_VERSION_DIR="$(pwd)/hugegraph-server/apache-hugegraph-server-$VERSION"
SERVER_BIN="$SERVER_VERSION_DIR/bin"
SERVER_LIB="$SERVER_VERSION_DIR/lib"

case "$COMPONENT" in
    server | hstore)
        COMPONENT_VERSION_DIR="$SERVER_VERSION_DIR"
        ;;
    pd)
        COMPONENT_VERSION_DIR="$(pwd)/hugegraph-pd/apache-hugegraph-pd-$VERSION"
        ;;
    store)
        COMPONENT_VERSION_DIR="$(pwd)/hugegraph-store/apache-hugegraph-store-$VERSION"
        ;;
    *)
        echo "Error: unsupported component '$COMPONENT' (expected server, pd, store, or hstore)" >&2
        exit 1
        ;;
esac
INSTALL_DEST_DIR="$COMPONENT_VERSION_DIR/library"

if [ ! -d "$SERVER_VERSION_DIR" ]; then
    echo "Error: SERVER_VERSION_DIR not found: $SERVER_VERSION_DIR" >&2
    exit 1
fi
if [ ! -d "$SERVER_LIB" ]; then
    echo "Error: SERVER_LIB dir not found: $SERVER_LIB" >&2
    exit 1
fi
if [ ! -d "$COMPONENT_VERSION_DIR" ]; then
    echo "Error: component dir not found: $COMPONENT_VERSION_DIR" >&2
    exit 1
fi

detect_rocksdb_provider() {
    local conf_dir="$1"
    local file
    local -a values=()
    local -a unique_values=()
    local value key conflicts
    local -A seen=()

    for file in "$conf_dir"/graphs/*.properties; do
        [ -f "$file" ] || continue
        mapfile -t -O "${#values[@]}" values < <(
            awk '
                /^[[:space:]]*#/ { next }
                /^[[:space:]]*rocksdb\.provider[[:space:]]*=/ {
                    value = $0
                    sub(/^[^=]*=[[:space:]]*/, "", value)
                    sub(/[[:space:]]+#.*/, "", value)
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
                    print value
                }
            ' "$file"
        )
    done
    for file in "$conf_dir"/application*.yml; do
        [ -f "$file" ] || continue
        mapfile -t -O "${#values[@]}" values < <(
            awk '
                /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
                /^[^[:space:]#][^:]*:/ {
                    in_rocksdb = ($0 ~ /^rocksdb[[:space:]]*:/)
                }
                in_rocksdb && /^[[:space:]]+provider[[:space:]]*:/ {
                    value = $0
                    sub(/^[^:]*:[[:space:]]*/, "", value)
                    sub(/[[:space:]]+#.*/, "", value)
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
                    if (value ~ /^"[^"]*"$/ || value ~ /^'\''[^'\'']*'\''$/) {
                        value = substr(value, 2, length(value) - 2)
                    }
                    print value
                }
            ' "$file"
        )
    done

    for value in "${values[@]}"; do
        key="provider:$value"
        if [ -z "${seen[$key]:-}" ]; then
            unique_values+=("$value")
            seen[$key]=true
        fi
    done

    if [ "${#unique_values[@]}" -gt 1 ]; then
        conflicts=$(IFS=,; echo "${unique_values[*]}")
        echo "Error: conflicting rocksdb.provider values: $conflicts" >&2
        return 1
    fi
    local provider="${unique_values[0]:-rocksdb}"
    case "$provider" in
        rocksdb | topling)
            echo "$provider"
            ;;
        *)
            echo "Error: invalid rocksdb.provider '$provider'; expected rocksdb or topling" >&2
            return 1
            ;;
    esac
}

PROVIDER=$(detect_rocksdb_provider "$COMPONENT_VERSION_DIR/conf") || exit 1

if [ "$PROVIDER" = "topling" ]; then
    if [ ! -f "$SERVER_BIN/common-topling.sh" ]; then
        echo "Error: common-topling.sh not found under: $SERVER_BIN" >&2
        exit 1
    fi

    source "$SERVER_BIN/common-topling.sh"
    type prepare_toplingdb >/dev/null 2>&1 || {
        echo "Error: function prepare_toplingdb not found" >&2
        exit 1
    }
    prepare_toplingdb "$SERVER_LIB/topling" "$INSTALL_DEST_DIR" \
                      "$COMPONENT_VERSION_DIR"
else
    echo "[install-rocksdb] $COMPONENT uses rocksdb provider (or unset)," \
         "skipping native preload"
fi

unset -f detect_rocksdb_provider

# A sourced script does not trigger EXIT on normal return, so restore explicitly.
# The EXIT trap above covers exit and errexit failure paths.
install_rocksdb_restore_state
unset -f install_rocksdb_restore_state
