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
trap 'echo "[common-topling] error at line ${LINENO}: ${BASH_COMMAND}" >&2' ERR

GITHUB="https://github.com"

function abs_path() {
    local SOURCE
    SOURCE="${BASH_SOURCE[0]}"
    while [[ -h "$SOURCE" ]]; do
        local DIR
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

    if [ ! -f "$jar_file" ]; then
        echo "'$jar_file' Not Exist" >&2
        return 1
    fi

    mkdir -p "$dest_dir" || {
        echo "Cannot mkdir '$dest_dir'" >&2
        return 1
    }

    if command -v realpath >/dev/null 2>&1; then
        abs_jar_path="$(realpath "$jar_file")"
    else
        abs_jar_path="$(readlink -f "$jar_file")"
    fi
    if ! command -v unzip >/dev/null 2>&1; then
        echo "Error: 'unzip' command not found. Please install unzip." >&2
        return 1
    fi
    unzip -j -o "$abs_jar_path" "*.so" -d "$dest_dir" > /dev/null 2>&1 || {
        local code=$?
        if [ $code -eq 11 ]; then
            echo "Error: No .so files found in '$abs_jar_path' (unzip exit 11)" >&2
        else
            echo "Error: unzip failed (exit $code) for '$abs_jar_path'" >&2
        fi
        return $code
    }
}

function extract_html_css_from_jar() {
    local jar_file="$1"
    local dest_dir="$2"
    local abs_jar_path
    # Prefer /dev/shm on Linux for speed; fallback to TMPDIR or /tmp
    local resource_target
    if [ "$(uname -s)" = "Linux" ] && [ -d /dev/shm ]; then
        resource_target="/dev/shm/rocksdb_resource"
    else
        resource_target="${TMPDIR:-/tmp}/rocksdb_resource"
    fi

    if [ ! -f "$jar_file" ]; then
        echo "Error: JAR file '$jar_file' does not exist." >&2
        return 1
    fi

    mkdir -p "$dest_dir" || {
        echo "Error: Cannot create destination directory '$dest_dir'." >&2
        return 1
    }

    if command -v realpath >/dev/null 2>&1; then
        abs_jar_path="$(realpath "$jar_file")"
    else
        abs_jar_path="$(readlink -f "$jar_file")"
    fi
    if ! command -v unzip >/dev/null 2>&1; then
        echo "Error: 'unzip' command not found. Please install unzip." >&2
        return 1
    fi
    unzip -j -o "$abs_jar_path" "*.html" "*.css" -d "$dest_dir" > /dev/null || {
        local code=$?
        if [ $code -eq 11 ]; then
            echo "Notice: No .html or .css files found in '$jar_file'." >&2
            return 0
        else
            echo "Error: unzip failed with exit code $code" >&2
            return $code
        fi
    }

    mkdir -p "$resource_target" || {
        echo "Error: Cannot create target directory '$resource_target'." >&2
        return 1
    }

    if compgen -G "$dest_dir"/*.html >/dev/null 2>&1; then
        cp -f "$dest_dir"/*.html "$resource_target"/
    fi
    if compgen -G "$dest_dir"/*.css >/dev/null 2>&1; then
        cp -f "$dest_dir"/*.css "$resource_target"/
    fi
}

function ensure_libaio_symlink() {
    # Check for Ubuntu 24.04+ and create a symlink for libaio if needed.
    # This is a workaround for software expecting the old libaio.so.1 name,
    # as it was renamed to libaio.so.1t64 in the new release.
    # https://askubuntu.com/questions/1512196/libaio1-on-noble/1516639#1516639
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        if [ "${ID:-}" = "ubuntu" ] && command -v dpkg >/dev/null 2>&1 && dpkg --compare-versions "${VERSION_ID:-0}" "ge" "24.04"; then
            local libaio_link_target="/usr/lib/x86_64-linux-gnu/libaio.so.1"
            if [ ! -e "$libaio_link_target" ]; then
                echo "Ubuntu ${VERSION_ID:-?} detected. Creating compatibility symlink for libaio."
                if [ -e /usr/lib/x86_64-linux-gnu/libaio.so.1t64 ]; then
                    if [ "$EUID" -eq 0 ]; then
                        ln -sf /usr/lib/x86_64-linux-gnu/libaio.so.1t64 "$libaio_link_target" || true
                    elif command -v sudo >/dev/null 2>&1; then
                        sudo ln -sf /usr/lib/x86_64-linux-gnu/libaio.so.1t64 "$libaio_link_target" || true
                    else
                        echo "Warn: sudo not available, skip creating $libaio_link_target" >&2
                    fi
                else
                    echo "Warn: libaio.so.1t64 not found, skip creating compat symlink" >&2
                fi
            fi
        fi
    fi
}

function download_and_verify() {
    local url=$1
    local filepath=$2
    local expected_md5=$3

    if [[ -f $filepath ]]; then
        echo "File $filepath exists. Verifying MD5 checksum..."
        actual_md5=$(md5sum $filepath | awk '{ print $1 }')
        if [[ $actual_md5 != $expected_md5 ]]; then
            echo "MD5 checksum verification failed for $filepath. Expected: $expected_md5, but got: $actual_md5"
            echo "Deleting $filepath..."
            rm -f $filepath
        else
            echo "MD5 checksum verification succeeded for $filepath."
            return 0
        fi
    fi

    echo "Downloading $filepath..."
    curl -L -o $filepath $url

    actual_md5=$(md5sum $filepath | awk '{ print $1 }')
    if [[ $actual_md5 != $expected_md5 ]]; then
        echo "MD5 checksum verification failed for $filepath after download. Expected: $expected_md5, but got: $actual_md5"
        return 1
    fi

    return 0
}

function download_and_setup_jemalloc() {
    local arch lib_file download_url expected_md5 system_lib top
    top=$1

    # Prefer system-installed jemalloc if available
    # Try ldconfig first to locate the shared object
    if command -v ldconfig >/dev/null 2>&1; then
        system_lib=$(ldconfig -p 2>/dev/null | awk '/jemalloc/{print $4}' | head -n1)
    fi
    # Fallback to common library paths if ldconfig is not available or found nothing
    if [[ -z "$system_lib" ]]; then
        for p in \
            /usr/lib/libjemalloc.so \
            /usr/lib/libjemalloc.so.2 \
            /usr/lib64/libjemalloc.so \
            /usr/lib64/libjemalloc.so.2 \
            /usr/local/lib/libjemalloc.so \
            /usr/local/lib/libjemalloc.so.2 \
            /usr/lib/x86_64-linux-gnu/libjemalloc.so \
            /usr/lib/x86_64-linux-gnu/libjemalloc.so.2 \
            /usr/lib/aarch64-linux-gnu/libjemalloc.so \
            /usr/lib/aarch64-linux-gnu/libjemalloc.so.2; do
            if [[ -f "$p" ]]; then
                system_lib="$p"
                break
            fi
        done
    fi

    # If found, set LD_PRELOAD and return immediately
    if [[ -n "$system_lib" ]]; then
        if [[ ":${LD_PRELOAD:-}:" != *"libjemalloc"* ]]; then
            export LD_PRELOAD="${system_lib}${LD_PRELOAD:+:$LD_PRELOAD}"
        fi
        return 0
    fi

    # Detect system architecture
    arch=$(uname -m)

    # System jemalloc not found, try to download the correct library for the architecture
    if [[ $arch == "aarch64" || $arch == "arm64" ]]; then
        lib_file="$top/bin/libjemalloc_aarch64.so"
        download_url="${GITHUB}/apache/hugegraph-doc/raw/binary-1.5/dist/server/libjemalloc_aarch64.so"
        expected_md5="2a631d2f81837f9d5864586761c5e380"
    elif [[ $arch == "x86_64" ]]; then
        lib_file="$top/bin/libjemalloc.so"
        download_url="${GITHUB}/apache/hugegraph-doc/raw/binary-1.5/dist/server/libjemalloc.so"
        expected_md5="fd61765eec3bfea961b646c269f298df"
    else
        echo "Unsupported architecture: $arch"
        return 1
    fi

    # Download and verify jemalloc library (fallback when system lib not found)
    if download_and_verify "$download_url" "$lib_file" "$expected_md5"; then
        if [[ ":${LD_PRELOAD:-}:" != *"libjemalloc.so:"* ]]; then
            export LD_PRELOAD="${lib_file}${LD_PRELOAD:+:$LD_PRELOAD}"
        fi
    else
        echo "Failed to verify or download jemalloc for $arch, skipping"
        return 1
    fi
}

function preload_toplingdb() {
    local lib_dir="$1"
    local dest_dir="$2"
    local top="$(cd "$lib_dir"/../ && pwd)"

    local jar_file
    jar_file=$(ls -1 "$lib_dir"/rocksdbjni*.jar 2>/dev/null | sort -V | tail -n1 || true)
    if [ -z "${jar_file:-}" ]; then
        echo "Error: No rocksdbjni*.jar found under '$lib_dir'" >&2
        return 1
    fi

    ensure_libaio_symlink
    download_and_setup_jemalloc "$top"
    extract_so_with_jar "$jar_file" "$dest_dir"
    if [ -d "$dest_dir" ]; then
        if [[ ":${LD_LIBRARY_PATH:-}:" != *":$dest_dir:"* ]]; then
            export LD_LIBRARY_PATH="$dest_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        fi

        if [ -f "$dest_dir/librocksdbjni-linux64.so" ] && [[ ":${LD_PRELOAD:-}:" != *"librocksdbjni-linux64.so:"* ]]; then
            export LD_PRELOAD="${LD_PRELOAD:+$LD_PRELOAD:}$dest_dir/librocksdbjni-linux64.so"
        fi

        # Persist environment for subsequent GitHub Actions steps
        # so LD_* variables survive across separate run blocks.
        if [ -n "${GITHUB_ENV:-}" ] && [ -w "$GITHUB_ENV" ]; then
            {
                echo "LD_LIBRARY_PATH=$LD_LIBRARY_PATH"
                echo "LD_PRELOAD=$LD_PRELOAD"
                echo "SERVER_VERSION_DIR=$SERVER_VERSION_DIR"
            } >> "$GITHUB_ENV" || true
            echo "[common-topling] Exported LD_LIBRARY_PATH and LD_PRELOAD to GITHUB_ENV" >&2 || true
        fi
    else
        echo "Warn: LD paths skipped, directory '$dest_dir' does not exist." >&2
    fi
    extract_html_css_from_jar "$jar_file" "$dest_dir"
}
