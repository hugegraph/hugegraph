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

source "$SERVER_BIN/common-topling.sh"
type preload_toplingdb >/dev/null 2>&1 || { echo "Error: function preload_toplingdb not found" >&2; exit 1; }
preload_toplingdb "$SERVER_LIB" "$DEST_DIR"

# Reset shell options to prevent affecting the parent shell when sourced
set +Eeuo pipefail
trap - ERR
# Restore original IFS
IFS="$ORIG_IFS"
