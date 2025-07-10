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

function abs_path() {
    SOURCE="${BASH_SOURCE[0]}"
    while [[ -h "$SOURCE" ]]; do
        DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
        SOURCE="$(readlink "$SOURCE")"
        [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
    done
    cd -P "$(dirname "$SOURCE")" && pwd
}

function extract_so_with_jar() {
    local jar_file="$1"
    local dest_dir="$2"
    local abs_jar_path
    local pipeline_status

    if [ ! -f "$jar_file" ]; then
      echo "'$jar_file' Not Exist" >&2
    fi

    if ! mkdir -p "$dest_dir"; then
      echo "Cannot mkdir '$dest_dir'" >&2
    fi

    if [[ "$jar_file" == /* ]]; then
      abs_jar_path="$jar_file"
    else
      abs_jar_path="$(pwd)/$jar_file"
    fi

    unzip -j -o "$abs_jar_path" "*.so" -d "$dest_dir" > /dev/null
    pipeline_status=$?

    if [ $pipeline_status -ne 0 ]; then
      # unzip provides specific exit codes that can be more descriptive.
      echo "Error: Failed to extract .so files with unzip (Exit Code: $pipeline_status)" >&2
    fi
}

function preload_toplingdb() {
  local jar_file=$(find $LIB -name "rocksdbjni*.jar")
  local dest_dir="$(pwd)"/$LIBRARY

  extract_so_with_jar $jar_file $dest_dir
  export LD_LIBRARY_PATH=$dest_dir:$LD_LIBRARY_PATH
  export LD_PRELOAD=libjemalloc.so:librocksdbjni-linux64.so
}

VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
SERVER_DIR=hugegraph-server/apache-hugegraph-server-incubating-$VERSION
LIB=$SERVER_DIR/lib
DB_CONF=$SERVER_DIR/conf/graphs/db_bench_community.yaml
LIBRARY=$SERVER_DIR/library
GITHUB="https://github.com"

preload_toplingdb
