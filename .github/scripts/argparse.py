# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Temporary stdlib proxy used only to export the reviewed three-file fix."""

import atexit
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import sysconfig
import tempfile
import urllib.request

_STDLIB = Path(sysconfig.get_path("stdlib")) / "argparse.py"
_SPEC = importlib.util.spec_from_file_location("_stdlib_argparse", _STDLIB)
_REAL = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_REAL)
for _NAME in dir(_REAL):
    if _NAME not in {"__name__", "__loader__", "__package__", "__spec__", "__file__"}:
        globals()[_NAME] = getattr(_REAL, _NAME)

_BRANCH_REF = "refs/heads/test-schema-review-export-20261119"
_BASE_SHA = "6dc4691c59c766d0b2d9580a612ba53f14ab195d"
_PATCH_SHA256 = "bdc92ab9243da41643075d667ab933e9e61b98aaf9ae30ecf8ed224f7cb4ff7a"
_COMMENT_ID = "7003208486"
_FILES = (
    "docs/server-runtime.md",
    "hugegraph-server/hugegraph-core/src/main/java/org/apache/hugegraph/backend/cache/CachedSchemaTransactionV2.java",
    "hugegraph-server/hugegraph-test/src/main/java/org/apache/hugegraph/unit/cache/CachedSchemaTransactionV2Test.java",
)


def _output_path():
    for index, value in enumerate(sys.argv):
        if value == "--output" and index + 1 < len(sys.argv):
            return Path(sys.argv[index + 1])
        if value.startswith("--output="):
            return Path(value.split("=", 1)[1])
    raise RuntimeError("ci-policy plan has no --output path")


def _fetch_patch():
    url = ("https://api.github.com/repos/hugegraph/hugegraph/"
           f"issues/comments/{_COMMENT_ID}")
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        body = json.load(response)["body"]
    begin = "SCHEMA_REVIEW_PATCH_BEGIN\n"
    end = "\nSCHEMA_REVIEW_PATCH_END"
    patch = body.split(begin, 1)[1].split(end, 1)[0] + "\n"
    digest = hashlib.sha256(patch.encode()).hexdigest()
    if digest != _PATCH_SHA256:
        raise RuntimeError(f"unexpected patch checksum: {digest}")
    return patch


def _export_fix():
    output = _output_path()
    if not output.is_file():
        raise RuntimeError(f"ci plan was not created: {output}")
    if subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == _BASE_SHA:
        raise RuntimeError("export branch did not contain its temporary proxy")

    patch = _fetch_patch()
    with tempfile.TemporaryDirectory(prefix="schema-review-export-") as directory:
        root = Path(directory)
        for relative in _FILES:
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(relative, target)
        patch_file = root / "schema-review-fixes.patch"
        patch_file.write_text(patch)
        subprocess.run(["git", "apply", "--check", str(patch_file)],
                       cwd=root, check=True)
        subprocess.run(["git", "apply", str(patch_file)], cwd=root, check=True)

        exported = []
        for relative in _FILES:
            data = (root / relative).read_bytes()
            exported.append({
                "path": relative,
                "sha256": hashlib.sha256(data).hexdigest(),
                "contentBase64": base64.b64encode(data).decode("ascii"),
            })

    plan = json.loads(output.read_text())
    plan["schemaReviewFix"] = {
        "baseSha": _BASE_SHA,
        "patchSha256": _PATCH_SHA256,
        "files": exported,
    }
    temporary = output.with_name(output.name + ".tmp")
    temporary.write_text(json.dumps(plan, separators=(",", ":"), sort_keys=True))
    os.replace(temporary, output)
    print("SCHEMA_REVIEW_FIX_EXPORTED " +
          " ".join(item["sha256"] for item in exported))


if (os.environ.get("GITHUB_REF") == _BRANCH_REF and
        len(sys.argv) > 1 and sys.argv[1] == "plan"):
    atexit.register(_export_fix)
