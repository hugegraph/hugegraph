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

# Copy into a caller-owned private directory before inspecting or hashing input.
# Structural checks reject ordinary RocksDB JARs; native compatibility is still
# established by the runtime smoke tests, not by filenames or this checksum.
set -Eeuo pipefail

if [ "$#" -ne 1 ] || [ ! -d "$1" ]; then
    echo "Usage: $0 <private-staging-directory>" >&2
    exit 1
fi
if [[ "${TOPLING_JNI_JAR:-}" != /* ]] ||
   [ ! -f "${TOPLING_JNI_JAR:-}" ] || [ ! -r "${TOPLING_JNI_JAR:-}" ]; then
    echo "Error: TOPLING_JNI_JAR must be an absolute readable external file" >&2
    exit 1
fi
if [[ ! "${TOPLING_JNI_SHA256:-}" =~ ^[[:xdigit:]]{64}$ ]]; then
    echo "Error: TOPLING_JNI_SHA256 must contain exactly 64 hexadecimal characters" >&2
    exit 1
fi

# A fixed name avoids interpreting externally supplied names as runtime layout.
STAGED_JAR="$1/rocksdbjni-topling.jar"
if [ -e "$STAGED_JAR" ] || [ -L "$STAGED_JAR" ]; then
    echo "Error: staging destination already exists: $STAGED_JAR" >&2
    exit 1
fi
cp "$TOPLING_JNI_JAR" "$STAGED_JAR"
ACTUAL_SHA256=$(sha256sum "$STAGED_JAR" | awk '{ print $1 }')
EXPECTED_SHA256=$(printf '%s' "$TOPLING_JNI_SHA256" | tr 'A-F' 'a-f')
if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "Error: staged Topling JNI SHA-256 mismatch" >&2
    exit 1
fi
ENTRIES=$(unzip -Z1 "$STAGED_JAR")
if [ "$(printf '%s\n' "$ENTRIES" | grep -cx 'org/rocksdb/SidePluginRepo.class' || true)" -ne 1 ] ||
   [ "$(printf '%s\n' "$ENTRIES" | grep -cx 'librocksdbjni-linux64.so' || true)" -ne 1 ]; then
    echo "Error: expected Topling Java marker and exactly one Linux x86_64 JNI library" >&2
    exit 1
fi
# Reject duplicate/nested copies which unzip -j would otherwise overwrite.
if [ "$(printf '%s\n' "$ENTRIES" | grep -Ec '(^|/)librocksdbjni-linux64[.]so$' || true)" -ne 1 ]; then
    echo "Error: ambiguous Linux x86_64 JNI library entries" >&2
    exit 1
fi
printf '%s\n' "$STAGED_JAR"
