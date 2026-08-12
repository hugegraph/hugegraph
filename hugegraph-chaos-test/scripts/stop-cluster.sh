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

HOME_DIR=$(pwd)

PROPERTIES_FILE="$HOME_DIR/hugegraph-commons/hugegraph-common/src/main/resources/version.properties"
if [ -f "$PROPERTIES_FILE" ]; then
    VERSION=$(grep "^VersionInBash=" "$PROPERTIES_FILE" | cut -d'=' -f2)
    if [ -z "$VERSION" ]; then
        echo "Error: VersionInBash not found in $PROPERTIES_FILE"
        exit 1
    fi
else
    echo "Error: properties file not found at $PROPERTIES_FILE"
    exit 1
fi

PD_DIR="$HOME_DIR/hugegraph-pd/apache-hugegraph-pd-$VERSION"
STORE_DIR="$HOME_DIR/hugegraph-store/apache-hugegraph-store-$VERSION"
SERVER_DIR="$HOME_DIR/hugegraph-server/apache-hugegraph-server-$VERSION"

echo "=== Stopping Server nodes ==="
for d in ${SERVER_DIR}_*; do
    if [ -d "$d" ]; then
        echo "Stopping $d"
        "$d/bin/stop-hugegraph.sh" 2>/dev/null || true
    fi
done

echo "=== Stopping Store nodes ==="
for d in ${STORE_DIR}_*; do
    if [ -d "$d" ]; then
        echo "Stopping $d"
        bash "$d/bin/stop-hugegraph-store.sh" 2>/dev/null || true
    fi
done

echo "=== Stopping PD nodes ==="
for d in ${PD_DIR}_*; do
    if [ -d "$d" ]; then
        echo "Stopping $d"
        bash "$d/bin/stop-hugegraph-pd.sh" 2>/dev/null || true
    fi
done

echo "=== Cluster stopped ==="
