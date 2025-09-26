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
IFS=$'\n\t'
# Unified error capture for easy positioning
trap 'echo "[install-rocksdb] error at line ${LINENO}: ${BASH_COMMAND}" >&2' ERR

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
SERVER_DIR="$(pwd)/hugegraph-server/apache-hugegraph-server-incubating-$VERSION"
BIN="$SERVER_DIR/bin"
LIB="$SERVER_DIR/lib"
DEST_DIR="$SERVER_DIR/library"

if [ ! -d "$SERVER_DIR" ]; then
    echo "Error: SERVER_DIR not found: $SERVER_DIR" >&2
    exit 1
fi
if [ ! -d "$LIB" ]; then
    echo "Error: LIB dir not found: $LIB" >&2
    exit 1
fi
if [ ! -f "$BIN/common-topling.sh" ]; then
    echo "Error: common-topling.sh not found under: $BIN" >&2
    exit 1
fi

source "$BIN/common-topling.sh"
type preload_toplingdb >/dev/null 2>&1 || { echo "Error: function preload_toplingdb not found" >&2; exit 1; }
preload_toplingdb "$LIB" "$DEST_DIR"
