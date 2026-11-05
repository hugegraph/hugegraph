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

"""Check effective-set diffs so moving a shared signature cannot hide a policy change."""

import unittest

from manifest_tool import BASELINE, ROOT, changes, grouped, profiles, validate_output


class ManifestDiffTest(unittest.TestCase):

    def test_common_redistribution_preserves_effective_sets(self):
        old = {"common": set(), "query": {"A#m()"}, "store_filter": {"A#m()"}, "session": {"Tx#commit()"}}
        new = {"common": {"A#m()"}, "query": set(), "store_filter": set(), "session": {"Tx#commit()"}}
        self.assertEqual(profiles(old), profiles(new))
        self.assertTrue(all(not diff["added"] and not diff["removed"] for diff in changes(old, new).values()))

    def test_profile_additions_and_removals_remain_visible(self):
        old = {"common": {"A#m()"}, "query": {"Graph#V()"}, "store_filter": set(), "session": {"Tx#commit()"}}
        new = {"common": set(), "query": {"A#m()", "Graph#V()", "Graph#V(int)"},
               "store_filter": set(), "session": set()}
        delta = changes(old, new)
        self.assertEqual(["Graph#V(int)"], delta["QUERY"]["added"])
        self.assertEqual(["A#m()"], delta["STORE_FILTER"]["removed"])
        self.assertEqual(["Tx#commit()"], delta["SESSION"]["removed"])
        self.assertEqual(delta["QUERY"], delta["SCHEMA"])
        self.assertEqual({"Graph#V": ["Graph#V()", "Graph#V(int)"]}, grouped(["Graph#V()", "Graph#V(int)"]))

    def test_cannot_overwrite_approval_locks(self):
        for output in (BASELINE, BASELINE.parent, BASELINE / "candidates"):
            with self.assertRaises(ValueError):
                validate_output(output)
        validate_output(ROOT / "target/script-policy-candidates")


if __name__ == "__main__":
    unittest.main()
