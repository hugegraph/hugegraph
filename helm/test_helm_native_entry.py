#!/usr/bin/env python3
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

"""Check public native HTTP entries without exposing engine peer protocols."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import yaml

from test_helm_vermeer import COMPONENT, INSTANCE, merged, pod, policy_allows, resources


CHART = Path(__file__).resolve().parent / "hugegraph"


class NativeEntryTest(unittest.TestCase):
    def render(self, mode="easy", topology="standalone", chart=CHART, **overrides):
        values = {
            "mode": mode,
            "pd": {"auth": {"value": "native-entry-pd-password"}},
            "server": {"auth": {"admin": {"password": "native-entry-server-password"},
                                  "token": {"value": "0123456789abcdef0123456789abcdef"}}},
            "vermeer": {"enabled": True, "mode": topology, "auth": {
                "value": "native-entry-factor-0123456789abcdef",
                "admin": {"password": "NativeEntryAdmin"}}},
            "seatunnel": {"enabled": True, "mode": topology,
                          "auth": {"password": "native-entry-seatunnel-password"}},
        }
        # None means the top-level mode is absent, as with legacy --reuse-values.
        if mode is None:
            values.pop("mode")
        values = merged(values, overrides)
        with tempfile.TemporaryDirectory(prefix="native-entry-values-") as directory:
            path = Path(directory) / "values.yaml"
            path.write_text(yaml.safe_dump(values))
            result = subprocess.run(["helm", "template", "entry", str(chart), "-f", str(path)],
                                    capture_output=True, text=True, timeout=120)
        self.assertEqual(0, result.returncode, result.stderr)
        return [doc for doc in yaml.safe_load_all(result.stdout) if doc]

    def native_services(self, documents):
        return [doc for doc in resources(documents, "Service")
                if doc["metadata"]["labels"].get(COMPONENT) in
                ("vermeer-master", "vermeer-worker", "seatunnel")]

    def assert_entries(self, documents, service_type, vermeer_port=6688):
        clients = [service for service in self.native_services(documents)
                   if service["spec"].get("clusterIP") != "None"]
        self.assertEqual(2, len(clients))
        for service in clients:
            component = service["metadata"]["labels"][COMPONENT]
            expected_port = 8080 if component == "seatunnel" else vermeer_port
            self.assertEqual(service_type, service["spec"]["type"])
            self.assertEqual([expected_port], [port["port"] for port in service["spec"]["ports"]])
            self.assertEqual(["http"], [port["name"] for port in service["spec"]["ports"]])
            self.assertEqual("http" if component == "seatunnel" else "master-http",
                             service["spec"]["ports"][0]["targetPort"])
        for service in self.native_services(documents):
            self.assertNotIn("externalIPs", service["spec"])
            if service not in clients:
                self.assertEqual("None", service["spec"]["clusterIP"])
                self.assertEqual("ClusterIP", service["spec"].get("type", "ClusterIP"))
                self.assertTrue(service["spec"]["publishNotReadyAddresses"])
                self.assertTrue(all("nodePort" not in port for port in service["spec"]["ports"]))
                self.assertTrue(all(port["port"] in (6689, 6789, 5801)
                                    for port in service["spec"]["ports"]))
        return clients

    def assert_ingress(self, documents, public_http, master_http=6688):
        policies = resources(documents, "NetworkPolicy")
        workloads = resources(documents, "StatefulSet")
        for component in ("vermeer-master", "seatunnel"):
            workload, = [doc for doc in workloads if doc["metadata"]["labels"].get(COMPONENT) == component]
            target = pod(workload)["metadata"]["labels"]
            stranger = {**target, INSTANCE: "other-release", COMPONENT: "untrusted-client"}
            http_port = 8080 if component == "seatunnel" else master_http
            self.assertEqual(public_http, policy_allows(policies, target, stranger, http_port, "ingress"))
            # Master and Worker share a Pod in standalone mode: opening all
            # ports on that Pod would expose Worker HTTP and both gRPC peers.
            private_ports = (5801,) if component == "seatunnel" else (6689, 6789, 6788)
            for port in private_ports:
                self.assertFalse(policy_allows(policies, target, stranger, port, "ingress"))
        for workload in workloads:
            if workload["metadata"]["labels"].get(COMPONENT) != "vermeer-worker":
                continue
            target = pod(workload)["metadata"]["labels"]
            stranger = {**target, INSTANCE: "other-release"}
            for port in (6788, 6789):
                self.assertFalse(policy_allows(policies, target, stranger, port, "ingress"))

    def test_mode_and_topology_matrix_exposes_only_authenticated_http(self):
        for mode in ("easy", "strict"):
            for topology in ("standalone", "cluster"):
                with self.subTest(mode=mode, topology=topology):
                    documents = self.render(mode=mode, topology=topology)
                    clients = self.assert_entries(documents, "NodePort" if mode == "easy" else "ClusterIP")
                    self.assertTrue(all("nodePort" not in port for service in clients
                                        for port in service["spec"]["ports"]))
                    self.assert_ingress(documents, mode == "easy")
                    native = [doc for doc in resources(documents, "StatefulSet")
                              if doc["metadata"]["labels"].get(COMPONENT, "").startswith("vermeer-")]
                    self.assertEqual(1 if topology == "standalone" else 2, len(native))
                    engine, = resources(documents, "StatefulSet", "seatunnel")
                    self.assertEqual(1 if topology == "standalone" else 2, engine["spec"]["replicas"])

    def test_explicit_type_controls_http_even_with_policy_switch_overrides(self):
        for mode in ("easy", "strict"):
            for service_type in ("ClusterIP", "NodePort"):
                with self.subTest(mode=mode, service_type=service_type):
                    documents = self.render(mode=mode,
                        vermeer={"service": {"type": service_type}},
                        seatunnel={"service": {"type": service_type}},
                        networkPolicy={"enabled": mode == "easy"})
                    self.assert_entries(documents, service_type)
                    self.assert_ingress(documents, service_type == "NodePort")

    def test_null_zero_and_custom_nodeport_ranges_preserve_http_targets(self):
        for vermeer_node, seatunnel_node in ((None, None), (0, 0), (8802, 8808), (65534, 65535)):
            with self.subTest(vermeer_node=vermeer_node, seatunnel_node=seatunnel_node):
                documents = self.render(mode="strict",
                    vermeer={"service": {"type": "NodePort", "nodePort": vermeer_node}},
                    seatunnel={"service": {"type": "NodePort", "nodePort": seatunnel_node}})
                clients = self.assert_entries(documents, "NodePort")
                for service in clients:
                    expected = seatunnel_node if service["metadata"]["labels"][COMPONENT] == "seatunnel" else vermeer_node
                    if expected:
                        self.assertEqual(expected, service["spec"]["ports"][0]["nodePort"])
                    else:
                        self.assertNotIn("nodePort", service["spec"]["ports"][0])

    def test_custom_master_http_port_is_used_for_service_policy_and_hubble(self):
        documents = self.render(mode="strict", topology="cluster",
            vermeer={"master": {"httpPort": 7688},
                     "service": {"type": "NodePort", "nodePort": 31688}},
            seatunnel={"service": {"type": "NodePort", "nodePort": 31880}})
        self.assert_entries(documents, "NodePort", vermeer_port=7688)
        self.assert_ingress(documents, True, master_http=7688)
        hubble, = resources(documents, "Deployment", "hubble")
        container, = pod(hubble)["spec"]["containers"]
        url, = [entry["value"] for entry in container["env"] if entry["name"] == "HG_HUBBLE_VERMEER_URL"]
        self.assertEqual("http://entry-hugegraph-vermeer-master:7688", url)

    def test_service_changes_do_not_roll_any_workload(self):
        before = self.render(topology="cluster")
        after = self.render(topology="cluster",
            vermeer={"service": {"type": "ClusterIP", "annotations": {"entry.example.com/owner": "ops"}}},
            seatunnel={"service": {"type": "ClusterIP", "annotations": {"entry.example.com/owner": "ops"}}})
        def templates(documents):
            return {doc["metadata"]["name"]: pod(doc) for doc in documents
                    if doc["kind"] in ("StatefulSet", "Deployment")}
        self.assertEqual(templates(before), templates(after))

    def test_legacy_missing_mode_keeps_clusterip_defaults(self):
        with tempfile.TemporaryDirectory(prefix="native-entry-legacy-") as directory:
            chart = Path(directory) / "chart"
            shutil.copytree(CHART, chart)
            values = yaml.safe_load((chart / "values.yaml").read_text())
            values.pop("mode")
            (chart / "values.yaml").write_text(yaml.safe_dump(values))
            documents = self.render(mode=None, chart=chart)
        self.assert_entries(documents, "ClusterIP")
        self.assert_ingress(documents, False)


if __name__ == "__main__":
    unittest.main()
