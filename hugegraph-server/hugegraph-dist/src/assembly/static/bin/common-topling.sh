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
    local resource_target="/dev/shm/rocksdb_resource"

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
            else
                echo "libaio.so.1 found, skip creating compatibility symlink" >&2
            fi
        fi
    fi
}

function preload_toplingdb() {
    local lib_dir="$1"
    local dest_dir="$2"

    local jar_file
    jar_file=$(find "$lib_dir" -maxdepth 1 -type f -name "rocksdbjni*.jar" -print -quit || true)
    if [ -z "${jar_file:-}" ]; then
        echo "Error: No rocksdbjni*.jar found under '$lib_dir'" >&2
        return 1
    fi

    ensure_libaio_symlink
    extract_so_with_jar "$jar_file" "$dest_dir"
    export LD_LIBRARY_PATH="$dest_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
    if [ -f "$dest_dir/librocksdbjni-linux64.so" ]; then
        export LD_PRELOAD="librocksdbjni-linux64.so${LD_PRELOAD:+:$LD_PRELOAD}"
    fi
    if command -v ldconfig >/dev/null 2>&1; then
        local jemalloc_found
        jemalloc_found=$(ldconfig -p 2>/dev/null | grep -F 'libjemalloc.so' || true)
        if [ -n "$jemalloc_found" ]; then
            export LD_PRELOAD="libjemalloc.so${LD_PRELOAD:+:$LD_PRELOAD}"
        fi
    fi
    extract_html_css_from_jar "$jar_file" "$dest_dir"
}
