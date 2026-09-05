#!/usr/bin/env bash
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
# Write PD's REST secret into a Hubble properties file as operations.pd.password.
#
#   usage: set-hubble-pd-password.sh <hubble.properties> [secret]
#
# The secret defaults to $HG_PD_AUTH_SECRET_KEY. The value never goes through a
# sed replacement, where & # and backslash are special, and backslashes are
# doubled for the .properties format. The file keeps its mode, which matters
# because Compose mounts it read-only into the Hubble container.
set -euo pipefail

file=${1:?usage: $0 <hubble.properties> [secret]}
secret=${2:-${HG_PD_AUTH_SECRET_KEY:-}}

[[ -f "$file" ]] || { echo "no such file: $file" >&2; exit 1; }
[[ -n "$secret" ]] || { echo "secret is empty; load .env first (set -a; . ./.env; set +a)" >&2; exit 1; }
case "$secret" in
    *$'\n'*|*$'\r'*) echo "secret contains a line break, which a .properties value cannot hold" >&2; exit 1 ;;
esac

escaped=${secret//\\/\\\\}
tmp=$(mktemp "${file}.XXXXXX")
trap 'rm -f "$tmp"' EXIT
grep -v '^operations\.pd\.password=' "$file" > "$tmp" || true
printf 'operations.pd.password=%s\n' "$escaped" >> "$tmp"
# cat, not mv: keep the file's inode and mode
cat "$tmp" > "$file"
