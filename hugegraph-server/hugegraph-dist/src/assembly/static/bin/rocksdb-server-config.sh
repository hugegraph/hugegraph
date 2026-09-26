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

# Server properties are authoritative for both Java and native runtime selection.
# Deliberately reject ambiguous syntax instead of approximating HugeConfig.
server_property() {
    local file="$1" key="$2" fallback="${3-}"
    [ -r "$file" ] || { echo "Error: unreadable configuration: $file" >&2; return 1; }
    awk -v wanted="$key" -v fallback="$fallback" '
        function fail(reason) {
            print "Error: " reason " for " wanted " in " FILENAME > "/dev/stderr"
            failed = 1
            exit 1
        }
        /^[[:space:]]*[#!]/ || /^[[:space:]]*$/ { next }
        {
            line = $0
            sub(/^[[:space:]]+/, "", line)
            key = line
            sub(/[[:space:]:=].*$/, "", key)
            if (key ~ /\\/) fail("escaped property keys are unsupported")
            if (key == "include" || key == "includeOptional") fail("property includes are unsupported")
            if (line ~ /\\$/) fail("property continuations are unsupported")
            if (key != wanted) next
            if (seen++) fail("duplicate property")
            value = substr(line, length(key) + 1)
            sub(/^[[:space:]]*/, "", value)
            sub(/^[:=][[:space:]]*/, "", value)
            sub(/[[:space:]]+$/, "", value)
            if (value ~ /\$\{/ || value ~ /,/) fail("interpolation/list syntax is unsupported")
            decoded = ""
            for (i = 1; i <= length(value); i++) {
                c = substr(value, i, 1)
                if (c == "\\") {
                    c = substr(value, ++i, 1)
                    if (c != " " && c != "\\" && c != ":" && c != "=" && c != "#" && c != "!") {
                        fail("unsupported escape or continuation")
                    }
                }
                decoded = decoded c
            }
        }
        END {
            if (!failed) print seen ? decoded : fallback
        }
    ' "$file"
}

server_graphs_directory() {
    local top="$1" rest="$2" graphs
    graphs=$(server_property "$rest" graphs './conf/graphs') || return 1
    [ -n "$graphs" ] || { echo "Error: empty graphs directory" >&2; return 1; }
    [[ "$graphs" = /* ]] || graphs="$top/$graphs"
    [ -d "$graphs" ] || { echo "Error: graphs directory does not exist: $graphs" >&2; return 1; }
    printf '%s\n' "$graphs"
}

server_rocksdb_provider() {
    local graphs="$1" file backend value provider=""
    for file in "$graphs"/*.properties; do
        [ -f "$file" ] || continue
        backend=$(server_property "$file" backend memory) || return 1
        backend=$(printf '%s' "$backend" | tr '[:upper:]' '[:lower:]')
        # Ignore remote/in-memory owners; a provider on them cannot load JNI.
        case "$backend" in rocksdb | rocksdbsst) ;; *) continue ;; esac
        value=$(server_property "$file" rocksdb.provider rocksdb) || return 1
        case "$value" in
            rocksdb | topling) ;;
            *) echo "Error: invalid rocksdb.provider '$value' in $file" >&2; return 1 ;;
        esac
        if [ -n "$provider" ] && [ "$provider" != "$value" ]; then
            echo "Error: conflicting rocksdb.provider values: $provider,$value" >&2
            return 1
        fi
        provider="$value"
    done
    printf '%s\n' "${provider:-rocksdb}"
}

server_check_provider_override() {
    local provider="$1" override="${TOPLINGDB_ROCKSDB_PROVIDER:-}"
    case "$override" in
        '' | rocksdb | topling) ;;
        *) echo "Error: invalid TOPLINGDB_ROCKSDB_PROVIDER '$override'" >&2; return 1 ;;
    esac
    if [ -n "$override" ] && [ "$override" != "$provider" ]; then
        echo "Error: TOPLINGDB_ROCKSDB_PROVIDER=$override conflicts with configured rocksdb.provider=$provider" >&2
        return 1
    fi
}

# Resolve storage paths without GNU realpath, including missing final directories.
# Canonicalizing only the distribution prefix permits macOS /var aliases while
# still rejecting a symlink configured below that prefix or in an external root.
server_lexical_path() {
    printf '%s\n' "$1" | awk '
        {
            count = split($0, parts, "/")
            depth = 0
            for (i = 1; i <= count; i++) {
                if (parts[i] == "" || parts[i] == ".") continue
                if (parts[i] == "..") {
                    if (depth > 0) depth--
                } else {
                    stack[++depth] = parts[i]
                }
            }
            result = ""
            for (i = 1; i <= depth; i++) result = result "/" stack[i]
            print (result == "" ? "/" : result)
        }
    '
}

server_storage_path() {
    local top="$1" path="$2" physical_top lexical physical probe suffix=""
    local part prefix=""
    local -a parts
    physical_top=$(cd -P "$top" && pwd) || return 1
    if [[ "$path" != /* ]]; then
        path="$physical_top/$path"
    else
        case "$path" in "$top" | "$top/"*) path="$physical_top${path#"$top"}" ;; esac
    fi
    IFS=/ read -r -a parts <<< "$path"
    for part in "${parts[@]}"; do
        [ -n "$part" ] || continue
        prefix="$prefix/$part"
        if [ -L "$prefix" ]; then
            echo "Error: RocksDB storage path contains a symlink: $path" >&2
            return 1
        fi
    done
    lexical=$(server_lexical_path "$path") || return 1
    probe="$path"
    while [ "$probe" != / ] && [[ "$probe" = */ ]]; do probe="${probe%/}"; done
    while [ ! -d "$probe" ]; do
        if [ -e "$probe" ] || [ -L "$probe" ]; then
            echo "Error: RocksDB storage path is not a directory: $probe" >&2
            return 1
        fi
        suffix="/${probe##*/}$suffix"
        probe=$(dirname "$probe")
    done
    physical=$(cd -P "$probe" && pwd) || return 1
    physical=$(server_lexical_path "$physical$suffix") || return 1
    [ "$lexical" = "$physical" ] || {
        echo "Error: RocksDB storage path contains a symlink: $path" >&2
        return 1
    }
    printf '%s\n' "$lexical"
}

# Standard RocksDB also runs on macOS. Inspect ownership without Linux flock/proc.
server_check_existing_markers() {
    local path="$1" provider="$2" current physical expected actual marker
    expected=$(printf '%s\n' format=1 component=server "provider=$provider")
    physical="$path"
    while [ ! -d "$physical" ] && [ "$physical" != / ]; do
        if [ -e "$physical" ] || [ -L "$physical" ]; then
            echo "Error: RocksDB storage path is not a directory: $physical" >&2
            return 1
        fi
        physical=$(dirname "$physical")
    done
    physical=$(cd -P "$physical" && pwd) || return 1
    # Inspect both the configured ancestry and resolved destination ancestry.
    # This catches a symlink into a differently marked deployment directory.
    for current in "$path" "$physical"; do
        while [ -n "$current" ]; do
            marker="$current/.hugegraph-rocksdb-provider"
            if [ -L "$marker" ] || { [ -e "$marker" ] && [ ! -f "$marker" ]; }; then
                echo "Error: provider marker is not a regular file: $marker" >&2
                return 1
            fi
            if [ ! -e "$marker" ] && [ -d "$current" ] &&
               find "$current" -mindepth 1 -maxdepth 1 -name '.provider-marker.*' -print -quit | grep -q .; then
                echo "Error: provider marker initialization is in progress in $current" >&2
                return 1
            fi
            if [ -f "$marker" ]; then
                [ -r "$marker" ] || { echo "Error: provider marker is not readable: $marker" >&2; return 1; }
                actual=$(cat "$marker") || return 1
                if [ "$actual" != "$expected" ]; then
                    echo "Error: provider marker mismatch in $current; expected server/$provider" >&2
                    return 1
                fi
            fi
            [ "$current" != / ] || break
            current=$(dirname "$current")
        done
    done
}

# Nonempty legacy standard roots remain readable without a migration. New or
# empty roots must be claimed before opening: otherwise a concurrent Topling
# startup could claim them between the legacy check and the first data write.
server_claim_standard_path() {
    local path="$1" current marker="$1/.hugegraph-rocksdb-provider"
    server_check_existing_markers "$path" rocksdb || return 1
    current="$path"
    while [ -n "$current" ]; do
        # A verified deployment ancestor already owns its descendants.
        [ ! -f "$current/.hugegraph-rocksdb-provider" ] || return 0
        [ "$current" != / ] || break
        current=$(dirname "$current")
    done
    mkdir -p "$path" || return 1
    # Check again after creating missing directories, before changing ownership.
    server_check_existing_markers "$path" rocksdb || return 1
    if find "$path" -mindepth 1 -maxdepth 1 ! -name lost+found \
            ! -name '.provider-marker.*' ! -name '.hugegraph-rocksdb-provider' -print -quit | grep -q .; then
        server_check_existing_markers "$path" rocksdb
        return $?
    fi
    # Bash noclobber atomically refuses an existing marker. Never replace the
    # winner, including an interrupted/partial marker: those fail validation.
    if ! (umask 077; set -C; printf '%s\n' format=1 component=server provider=rocksdb > "$marker") 2>/dev/null; then
        [ -f "$marker" ] || { echo "Error: could not claim provider marker: $marker" >&2; return 1; }
    fi
    server_check_existing_markers "$path" rocksdb || return 1
    [ -f "$marker" ] || { echo "Error: provider marker disappeared: $marker" >&2; return 1; }
}

# A configured data/WAL directory may sit below a deployment-owned marked root.
# Check every marked ancestor too, so a nested directory cannot bypass a marker.
server_verify_storage_path() {
    local top="$1" provider="$2" path="$3" enforce="$4"
    local current marked=false verified_root="${5:-}"
    path=$(server_storage_path "$top" "$path") || return 1
    [ "$path" != / ] || { echo "Error: RocksDB data path cannot be /" >&2; return 1; }
    if [ "$provider" = rocksdb ] && [ "$enforce" = false ]; then
        server_claim_standard_path "$path"
        return $?
    fi
    current="${path%/}"
    while [ -n "$current" ] && [ "$current" != / ]; do
        if [ -e "$current/.hugegraph-rocksdb-provider" ] ||
           [ -L "$current/.hugegraph-rocksdb-provider" ]; then
            "$top/bin/verify-rocksdb-provider.sh" server "$provider" "$current" "$enforce" || return 1
            marked=true
        fi
        current="${current%/*}"
    done
    # The Docker-generated root was already checked, including legacy unmarked
    # RocksDB mode. Its data/WAL children need not exist before first init.
    if [ -n "$verified_root" ]; then
        verified_root=$(server_storage_path "$top" "$verified_root") || return 1
        case "$path/" in "$verified_root/"*) marked=true ;; esac
    fi
    if [ "$marked" = false ]; then
        "$top/bin/verify-rocksdb-provider.sh" server "$provider" "$path" "$enforce" || return 1
    fi
}

server_verify_graph_roots() {
    local top="$1" graphs="$2" provider="$3" enforce="$4"
    local file backend disks path key verified_root="${5:-}"
    case "$enforce" in
        true | false) ;;
        *) echo "Error: provider marker enforcement must be true or false" >&2; return 1 ;;
    esac
    for file in "$graphs"/*.properties; do
        [ -f "$file" ] || continue
        backend=$(server_property "$file" backend memory) || return 1
        backend=$(printf '%s' "$backend" | tr '[:upper:]' '[:lower:]')
        case "$backend" in rocksdb | rocksdbsst) ;; *) continue ;; esac
        disks=$(server_property "$file" rocksdb.data_disks '') || return 1
        [ -z "$disks" ] || {
            echo "Error: provider marker validation does not support rocksdb.data_disks in $file" >&2
            return 1
        }
        for key in data wal; do
            path=$(server_property "$file" "rocksdb.${key}_path" "rocksdb-data/$key") || return 1
            [ -n "$path" ] || { echo "Error: empty rocksdb.${key}_path in $file" >&2; return 1; }
            server_verify_storage_path "$top" "$provider" "$path" "$enforce" "$verified_root" || return 1
        done
    done
}
