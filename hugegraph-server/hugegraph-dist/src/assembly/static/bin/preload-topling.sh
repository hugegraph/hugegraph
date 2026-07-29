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
# Save original IFS to avoid leaking into parent shell when sourced
ORIG_IFS="${IFS}"
IFS=$'\n\t'
# Unified error capture for easy positioning
trap 'echo "[preload-topling] error at line ${LINENO}: ${BASH_COMMAND}" >&2' ERR

SERVER_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_TOP="$(cd "$SERVER_BIN"/../ && pwd)"
SERVER_LIB="$SERVER_TOP/lib"
DEST_DIR="$SERVER_TOP/library"

if [ ! -d "$SERVER_LIB" ]; then
    echo "Error: LIB dir not found: $SERVER_LIB" >&2
    exit 1
fi
if [ ! -f "$SERVER_BIN/common-topling.sh" ]; then
    echo "Error: common-topling.sh not found under: $SERVER_BIN" >&2
    exit 1
fi

# Detect if ToplingDB provider is configured
# Check both .properties files (hugegraph-server) and .yml files (HStore)
PROVIDER=$(grep -sh "rocksdb.provider" "$SERVER_TOP"/conf/graphs/*.properties 2>/dev/null \
           | grep -v "^#" | grep -o "topling" | head -1 || true)
if [ -z "$PROVIDER" ]; then
    PROVIDER=$(grep -sh "provider:" "$SERVER_TOP"/conf/application*.yml 2>/dev/null \
               | grep -v "^#" | grep -o "topling" | head -1 || true)
fi

if [ "$PROVIDER" = "topling" ]; then
    # --- ToplingDB mode: swap JAR and set up environment ---

    source "$SERVER_BIN/common-topling.sh"
    type require_topling_platform >/dev/null 2>&1 || {
        echo "Error: function require_topling_platform not found" >&2
        exit 1
    }
    require_topling_platform || exit 1

    TOPLING_JAR=$(ls -1 "$SERVER_LIB"/topling/rocksdbjni*.jar 2>/dev/null | sort -V | tail -1 || true)
    if [ -z "${TOPLING_JAR:-}" ]; then
        echo "Error: rocksdb.provider=topling but no ToplingDB JAR found in $SERVER_LIB/topling/" >&2
        echo "       Please place the ToplingDB rocksdbjni JAR in lib/topling/ directory." >&2
        exit 1
    fi

    # Swap: move standard JAR aside, copy ToplingDB JAR into lib/
    STANDARD_JAR=$(ls -1 "$SERVER_LIB"/rocksdbjni*.jar 2>/dev/null | sort -V | tail -1 || true)
    if [ -n "$STANDARD_JAR" ]; then
        mv "$STANDARD_JAR" "$SERVER_LIB/topling/.standard-backup.jar"
        echo "[preload-topling] Backed up standard JAR: $(basename "$STANDARD_JAR")"
    fi
    cp "$TOPLING_JAR" "$SERVER_LIB/"
    echo "[preload-topling] Activated ToplingDB JAR: $(basename "$TOPLING_JAR")"

    # Run native library preload (extract .so, set LD_PRELOAD/LD_LIBRARY_PATH, jemalloc)
    type preload_toplingdb >/dev/null 2>&1 || { echo "Error: function preload_toplingdb not found" >&2; exit 1; }
    preload_toplingdb "$SERVER_LIB" "$DEST_DIR"

    # Prefer an Easy Migrate config supplied by the caller;
    # otherwise check the existing Server and HStore config locations.
    CONF_FILE="${TOPLINGDB_EASY_MIGRATE_CONF:-}"
    if [ -z "$CONF_FILE" ]; then
        CONF_FILE="$SERVER_TOP/conf/toplingdb.yaml"
        if [ ! -f "$CONF_FILE" ]; then
            CONF_FILE="$SERVER_TOP/conf/rocksdb_store.yaml"
        fi
    fi
    if [ -f "$CONF_FILE" ]; then
        export TOPLINGDB_EASY_MIGRATE_CONF="$CONF_FILE"
        echo "[preload-topling] TOPLINGDB_EASY_MIGRATE_CONF=$CONF_FILE"
    else
        echo "[preload-topling] Warning: no ToplingDB config found, advanced features may not activate" >&2
    fi

    # Persist for GitHub Actions
    if [ -n "${GITHUB_ENV:-}" ] && [ -w "$GITHUB_ENV" ]; then
        echo "TOPLINGDB_EASY_MIGRATE_CONF=$TOPLINGDB_EASY_MIGRATE_CONF" >> "$GITHUB_ENV" || true
    fi
else
    # --- Standard RocksDB mode: no ToplingDB setup needed ---
    echo "[preload-topling] rocksdb.provider=standard (or unset), skipping ToplingDB setup"
fi

# Reset shell options to prevent affecting the parent shell when sourced
set +Eeuo pipefail
trap - ERR
# Restore original IFS
IFS="$ORIG_IFS"
