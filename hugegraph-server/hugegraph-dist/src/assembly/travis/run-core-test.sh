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
set -ev

BACKEND=$1
TRAVIS_DIR=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$TRAVIS_DIR/../../../../.." && pwd)

if [[ "$BACKEND" == "rocksdb" &&
      "$(uname -s)" == "Linux" &&
      "$(uname -m)" == "riscv64" ]]; then
    . "$TRAVIS_DIR/../static/bin/util.sh"
    configure_riscv64_libatomic
    cd "$REPO_ROOT"
    mvn test -pl hugegraph-server/hugegraph-test -am \
        -P core-test,"$BACKEND" -Drocksdb-only
else
    cd "$REPO_ROOT"
    mvn test -pl hugegraph-server/hugegraph-test -am \
        -P core-test,"$BACKEND"
fi
