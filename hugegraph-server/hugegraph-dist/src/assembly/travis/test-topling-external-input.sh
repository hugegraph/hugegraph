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

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../../../../.." && pwd)"
TEST_ROOT=$(mktemp -d)
trap 'rm -rf "$TEST_ROOT"' EXIT
HELPER="$PROJECT_ROOT/install-dist/scripts/stage-topling-jni.sh"
INSTALLER="$SCRIPT_DIR/install-rocksdb.sh"
mkdir -p "$TEST_ROOT/external files" "$TEST_ROOT/bin" "$TEST_ROOT/repo/install-dist/scripts"
cp "$HELPER" "$PROJECT_ROOT/install-dist/scripts/build-topling-distribution.sh" \
    "$TEST_ROOT/repo/install-dist/scripts/"
python3 - "$TEST_ROOT/external files" <<'FIXTURE'
import sys
import zipfile
from pathlib import Path
root = Path(sys.argv[1])
for name, marker, duplicate in [("provided input.jar", True, False),
                                 ("standard.jar", False, False),
                                 ("duplicate.jar", True, True)]:
    with zipfile.ZipFile(root / name, "w") as jar:
        jar.writestr("librocksdbjni-linux64.so", "structural fixture only")
        if marker:
            jar.writestr("org/rocksdb/SidePluginRepo.class", "structural fixture only")
        if duplicate:
            jar.writestr("nested/librocksdbjni-linux64.so", "duplicate")
FIXTURE
export TOPLING_JNI_JAR="$TEST_ROOT/external files/provided input.jar"
export TOPLING_JNI_SHA256=$(sha256sum "$TOPLING_JNI_JAR" | awk '{print $1}')
VALID_JAR="$TOPLING_JNI_JAR"
VALID_SHA="$TOPLING_JNI_SHA256"

fail() { echo "FAIL: $*" >&2; exit 1; }
expect_rejected() {
    local name="$1"
    shift
    mkdir "$TEST_ROOT/$name"
    if "$@" bash "$HELPER" "$TEST_ROOT/$name" >"$TEST_ROOT/error.log" 2>&1; then
        fail "$name was accepted"
    fi
}
expect_rejected missing-pair env -u TOPLING_JNI_JAR -u TOPLING_JNI_SHA256
expect_rejected missing-file env -u TOPLING_JNI_JAR
expect_rejected missing-hash env -u TOPLING_JNI_SHA256
expect_rejected relative env TOPLING_JNI_JAR=relative.jar
expect_rejected invalid-hash env TOPLING_JNI_SHA256=invalid
expect_rejected mismatch env TOPLING_JNI_SHA256=$(printf '%064d' 0)
expect_rejected unreadable env TOPLING_JNI_JAR="$TEST_ROOT/not-present.jar"
cp "$VALID_JAR" "$TEST_ROOT/unreadable.jar"
chmod 000 "$TEST_ROOT/unreadable.jar"
if [ ! -r "$TEST_ROOT/unreadable.jar" ]; then
    expect_rejected unreadable-mode env TOPLING_JNI_JAR="$TEST_ROOT/unreadable.jar"
fi
chmod 600 "$TEST_ROOT/unreadable.jar"
for name in standard duplicate; do
    jar="$TEST_ROOT/external files/$name.jar"
    sha=$(sha256sum "$jar" | awk '{print $1}')
    expect_rejected "$name" env TOPLING_JNI_JAR="$jar" TOPLING_JNI_SHA256="$sha"
done
mkdir "$TEST_ROOT/valid staged"
result=$(bash "$HELPER" "$TEST_ROOT/valid staged")
[ "$result" = "$TEST_ROOT/valid staged/rocksdbjni-topling.jar" ] || fail 'canonical staged name'
cmp "$VALID_JAR" "$result" || fail 'staged copy differs'

# Force a post-copy source change: hashing the source would reject this good copy.
REAL_CP=$(command -v cp)
cat > "$TEST_ROOT/bin/cp" <<'FIXTURE'
#!/bin/bash
"$REAL_CP" "$@"
printf 'changed after copy' > "$TOPLING_JNI_JAR"
FIXTURE
chmod +x "$TEST_ROOT/bin/cp"
export REAL_CP
mkdir "$TEST_ROOT/toctou"
PATH="$TEST_ROOT/bin:$PATH" bash "$HELPER" "$TEST_ROOT/toctou" >/dev/null
cmp "$result" "$TEST_ROOT/toctou/rocksdbjni-topling.jar" || fail 'staged checksum'
"$REAL_CP" "$result" "$VALID_JAR"
rm "$TEST_ROOT/bin/cp"

# The real installer/build entry points use cheap fixtures, no Maven or native execution.
cat > "$TEST_ROOT/bin/uname" <<'FIXTURE'
#!/bin/bash
case "$1" in -s) echo Linux ;; -m) echo x86_64 ;; esac
FIXTURE
cat > "$TEST_ROOT/bin/mvn" <<'FIXTURE'
#!/bin/bash
echo 1.0
FIXTURE
chmod +x "$TEST_ROOT/bin/"*
export PATH="$TEST_ROOT/bin:$PATH"
DIST="$TEST_ROOT/repo/hugegraph-server/apache-hugegraph-server-1.0"
mkdir -p "$DIST/conf/graphs" "$DIST/bin" "$DIST/lib/topling" "$DIST/library"
printf 'rocksdb.provider=rocksdb\n' > "$DIST/conf/graphs/hugegraph.properties"
printf 'existing jar' > "$DIST/lib/topling/rocksdbjni-existing.jar"
printf 'existing native' > "$DIST/library/librocksdbjni-linux64.so"
cat > "$DIST/bin/prepare-topling.sh" <<'FIXTURE'
#!/bin/bash
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$root/library"
printf 'prepared fixture' > "$root/library/librocksdbjni-linux64.so"
FIXTURE
chmod +x "$DIST/bin/prepare-topling.sh"
(cd "$TEST_ROOT/repo" && env -u TOPLING_JNI_JAR -u TOPLING_JNI_SHA256 bash "$INSTALLER")
printf 'rocksdb.provider=topling\n' > "$DIST/conf/graphs/hugegraph.properties"
if (cd "$TEST_ROOT/repo" && env -u TOPLING_JNI_JAR -u TOPLING_JNI_SHA256 \
        bash "$INSTALLER") >"$TEST_ROOT/error.log" 2>&1; then
    fail 'installer used existing runtime as fallback'
fi
[ "$(cat "$DIST/lib/topling/rocksdbjni-existing.jar")" = 'existing jar' ] || fail 'installer changed old jar'
[ "$(cat "$DIST/library/librocksdbjni-linux64.so")" = 'existing native' ] || fail 'installer changed old native'
TOPLING_DIST="$DIST-topling"
mkdir "$TOPLING_DIST"
printf 'previous output' > "$TOPLING_DIST/keep"
printf 'previous archive' > "$TOPLING_DIST.tar.gz"
if env TOPLING_JNI_SHA256=$(printf '%064d' 0) bash \
        "$TEST_ROOT/repo/install-dist/scripts/build-topling-distribution.sh" server 1.0 \
        >"$TEST_ROOT/error.log" 2>&1; then
    fail 'distribution accepted checksum mismatch'
fi
[ "$(cat "$TOPLING_DIST/keep")" = 'previous output' ] || fail 'distribution changed output'
[ "$(cat "$TOPLING_DIST.tar.gz")" = 'previous archive' ] || fail 'distribution changed archive'
# Inject failures after validation: native copy, then the second publish rename.
REAL_MV=$(command -v mv)
REAL_SED=$(command -v sed)
export REAL_MV REAL_SED
cat > "$TEST_ROOT/bin/mv" <<'FIXTURE'
#!/bin/bash
case "${FAIL_MV_KIND:-}:$1" in
    native:*/merged-library|archive:*/.topling-dist.*/apache-hugegraph-server-1.0-topling.tar.gz)
        echo injected-publish-failure >&2
        exit 1 ;;
esac
exec "$REAL_MV" "$@"
FIXTURE
cat > "$TEST_ROOT/bin/cp" <<'FIXTURE'
#!/bin/bash
if [ "${FAIL_NATIVE_COPY:-false}" = true ] && [[ "$*" == *'/merged-library/'* ]]; then
    echo injected-copy-failure >&2
    exit 1
fi
exec "$REAL_CP" "$@"
FIXTURE
# Keep the builder's GNU sed invocation portable in this structural fixture.
cat > "$TEST_ROOT/bin/sed" <<'FIXTURE'
#!/bin/bash
if [ "$1" = -i ]; then
    shift
    file="${@: -1}"
    "$REAL_SED" "$@" > "$file.tmp" && "$REAL_MV" "$file.tmp" "$file"
else
    exec "$REAL_SED" "$@"
fi
FIXTURE
chmod +x "$TEST_ROOT/bin/mv" "$TEST_ROOT/bin/cp" "$TEST_ROOT/bin/sed"
assert_old_runtime() {
    [ "$(cat "$DIST/lib/topling/rocksdbjni-existing.jar")" = 'existing jar' ] || fail 'old jar lost'
    [ "$(cat "$DIST/library/librocksdbjni-linux64.so")" = 'existing native' ] || fail 'old native lost'
    [ ! -e "$DIST/lib/topling/rocksdbjni-topling.jar" ] || fail 'new jar leaked after failure'
}
for mode in copy publish; do
    if (cd "$TEST_ROOT/repo" && FAIL_NATIVE_COPY=$([ "$mode" = copy ] && echo true || echo false) \
        FAIL_MV_KIND=$([ "$mode" = publish ] && echo native || echo none) \
        bash "$INSTALLER") >"$TEST_ROOT/error.log" 2>&1; then
        fail "installer accepted $mode failure"
    fi
    grep -q 'injected-.*-failure' "$TEST_ROOT/error.log" || fail "$mode injection not reached"
    assert_old_runtime
    [ -z "$(find "$DIST" -maxdepth 1 -name '.topling-input.*' -print)" ] || fail 'installer staging leaked'
done
# A final runtime path of the wrong type is rejected without touching the JAR.
mv "$DIST/library" "$DIST/saved-library"
printf 'not a directory' > "$DIST/library"
if (cd "$TEST_ROOT/repo" && bash "$INSTALLER") >"$TEST_ROOT/error.log" 2>&1; then
    fail 'installer accepted non-directory library'
fi
[ "$(cat "$DIST/library")" = 'not a directory' ] || fail 'installer changed invalid target'
rm "$DIST/library"
mv "$DIST/saved-library" "$DIST/library"
assert_old_runtime

# Builder fixture uses a clean standard package; keep the installed runtime aside.
mv "$DIST/lib/topling" "$TEST_ROOT/saved-topling"
printf 'rocksdb.provider=rocksdb\n' > "$DIST/conf/graphs/hugegraph.properties"
if FAIL_MV_KIND=archive bash "$TEST_ROOT/repo/install-dist/scripts/build-topling-distribution.sh" \
    server 1.0 >"$TEST_ROOT/error.log" 2>&1; then
    fail 'builder accepted archive publication failure'
fi
grep -q injected-publish-failure "$TEST_ROOT/error.log" || {
    cat "$TEST_ROOT/error.log"
    fail 'archive injection not reached'
}
[ "$(cat "$TOPLING_DIST/keep")" = 'previous output' ] || fail 'builder rollback lost directory'
[ "$(cat "$TOPLING_DIST.tar.gz")" = 'previous archive' ] || fail 'builder rollback lost archive'
[ -z "$(find "$(dirname "$DIST")" -maxdepth 1 -name '.topling-dist.*' -print)" ] || fail 'builder staging leaked'
# Reproduce the original directory-at-archive-target failure before any mutation.
rm "$TOPLING_DIST.tar.gz"
mkdir "$TOPLING_DIST.tar.gz"
printf 'keep invalid target' > "$TOPLING_DIST.tar.gz/keep"
if bash "$TEST_ROOT/repo/install-dist/scripts/build-topling-distribution.sh" server 1.0 \
    >"$TEST_ROOT/error.log" 2>&1; then
    fail 'builder accepted directory archive target'
fi
[ "$(cat "$TOPLING_DIST/keep")" = 'previous output' ] || fail 'builder changed directory on target error'
[ "$(cat "$TOPLING_DIST.tar.gz/keep")" = 'keep invalid target' ] || fail 'builder changed invalid archive'
rm -r "$TOPLING_DIST.tar.gz"
bash "$TEST_ROOT/repo/install-dist/scripts/build-topling-distribution.sh" server 1.0 >/dev/null
[ -f "$TOPLING_DIST/lib/topling/rocksdbjni-topling.jar" ] || fail 'builder publication missing jar'
[ -f "$TOPLING_DIST.tar.gz" ] || fail 'builder publication missing archive'
mv "$TEST_ROOT/saved-topling" "$DIST/lib/topling"
printf 'rocksdb.provider=topling\n' > "$DIST/conf/graphs/hugegraph.properties"
printf 'unrelated library' > "$DIST/library/keep.so"
# Sourcing must preserve caller flags, IFS and traps on successful return.
(cd "$TEST_ROOT/repo" && INSTALLER="$INSTALLER" bash -c '
    set +e +u +E
    set +o pipefail
    IFS=:
    trap ": caller-err" ERR
    trap ": caller-exit" EXIT
    before_flags=$-; before_err=$(trap -p ERR); before_exit=$(trap -p EXIT)
    source "$INSTALLER"
    [ "$before_flags" = "$-" ] && [ "$IFS" = : ] &&
    [ "$before_err" = "$(trap -p ERR)" ] && [ "$before_exit" = "$(trap -p EXIT)" ]
') || fail 'sourced installer did not restore shell state'
[ "$(cat "$DIST/library/keep.so")" = 'unrelated library' ] || fail 'unrelated library lost'
(cd "$TEST_ROOT/repo" && bash "$INSTALLER")
[ -f "$DIST/lib/topling/rocksdbjni-topling.jar" ] || fail 'installer canonical jar missing'
[ ! -e "$DIST/lib/topling/rocksdbjni-existing.jar" ] || fail 'installer left duplicate jar'
[ "$(cat "$DIST/library/librocksdbjni-linux64.so")" = 'prepared fixture' ] || fail 'installer native missing'
echo 'PASS: external Topling JNI input contract (structural fixtures; no native execution)'
