#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Generate reviewable method-lock candidates and report effective profile changes.

Run with Java 17: python3 install-dist/scripts/script-policy/manifest_tool.py check
The checked-in manifests are never overwritten. No runtime policy flag is changed.
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
BASELINE = ROOT / "hugegraph-struct/src/main/resources/org/apache/hugegraph/security/script"
FILES = ("common", "query", "store_filter", "session")


def manifests(directory):
    return {name: {line.strip() for line in (directory / (name + "-methods.txt")).read_text().splitlines()
                   if line.strip() and not line.startswith("#")}
            for name in FILES}


def profiles(parts):
    query = parts["common"] | parts["query"]
    return {"QUERY": query, "SCHEMA": query, "SESSION": query | parts["session"],
            "STORE_FILTER": parts["common"] | parts["store_filter"]}


def changes(before, after):
    old, new = profiles(before), profiles(after)
    return {name: {"added": sorted(new[name] - old[name]), "removed": sorted(old[name] - new[name])}
            for name in old}


def grouped(signatures):
    result = {}
    for signature in signatures:
        result.setdefault(signature.split("(", 1)[0], []).append(signature)
    return result


def report_text(delta):
    lines = ["Effective method policy changes (overloads grouped by declaring type and method):"]
    for profile, diff in delta.items():
        lines.append(f"{profile}: +{len(diff['added'])} / -{len(diff['removed'])}")
        for action, marker in (("added", "+"), ("removed", "-")):
            for method, signatures in grouped(diff[action]).items():
                lines.append(f"  {marker} {method}: {len(signatures)} overload(s)")
                lines.extend(f"      {signature}" for signature in signatures)
    return "\n".join(lines) + "\n"


def validate_output(output):
    # A candidate location must never overlap the checked-in approval locks.
    if output == BASELINE or BASELINE in output.parents or output in BASELINE.parents:
        raise ValueError("Candidate output must not overlap the checked-in manifests")


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin" / name) if home else name


def generate(output, classpath_file):
    if classpath_file is None:
        # Package upstream reactor artifacts before resolving the generator classpath.
        subprocess.run(["mvn", "-B", "-ntp", "-pl", "hugegraph-server/hugegraph-core", "-am",
                        "package", "dependency:build-classpath", "-DskipTests", "-Dmaven.javadoc.skip=true",
                        "-DincludeScope=runtime", "-Dmdep.outputFile=target/script-policy-classpath.txt"],
                       cwd=ROOT, check=True)
        classpath_file = ROOT / "hugegraph-server/hugegraph-core/target/script-policy-classpath.txt"
    classpath = os.pathsep.join([str(ROOT / "hugegraph-server/hugegraph-core/target/classes"),
                                 str(ROOT / "hugegraph-struct/target/classes"),
                                 classpath_file.read_text().strip()])
    with tempfile.TemporaryDirectory(prefix="hugegraph-policy-generator-") as classes:
        subprocess.run([java_tool("javac"), "--release", "17", "-cp", classpath, "-d", classes,
                        str(Path(__file__).with_name("GenerateScriptPolicy.java"))], check=True)
        subprocess.run([java_tool("java"), "-cp", os.pathsep.join([classes, classpath]),
                        "org.apache.hugegraph.security.script.GenerateScriptPolicy",
                        str(output), str(BASELINE)], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("check", "generate"), nargs="?", default="check")
    parser.add_argument("--output", type=Path, default=ROOT / "target/script-policy-candidates")
    parser.add_argument("--classpath-file", type=Path,
                        help="Reuse a current core runtime classpath after building the reactor")
    args = parser.parse_args()
    output = args.output.resolve()
    validate_output(output)
    generate(output, args.classpath_file)
    before, after = manifests(BASELINE), manifests(output)
    delta = changes(before, after)
    report = {"counts": {name: len(signatures) for name, signatures in after.items()},
              "profiles": delta}
    (output / "changes.json").write_text(json.dumps(report, indent=2) + "\n")
    summary = report_text(delta)
    (output / "changes.txt").write_text(summary)
    print(summary, end="")
    print(f"Candidates and reports: {output}")
    drift = any(diff["added"] or diff["removed"] for diff in delta.values())
    return 1 if args.action == "check" and drift else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Policy manifest generation failed: {error}", file=sys.stderr)
        sys.exit(2)
