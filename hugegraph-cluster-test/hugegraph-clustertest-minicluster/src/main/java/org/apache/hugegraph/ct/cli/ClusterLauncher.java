/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.ct.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;

import org.apache.hugegraph.ct.client.HugeGraphCluster;
import org.apache.hugegraph.ct.config.ClusterTestConfig;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "cluster-launcher", mixinStandardHelpOptions = true,
         description = "HugeGraph cluster launcher and test runner")
public class ClusterLauncher implements Runnable {

    @Option(names = "--config", description = "YAML config file name")
    private String config;

    @Option(names = "--pd", description = "Number of PD nodes")
    private Integer pd;

    @Option(names = "--store", description = "Number of Store nodes")
    private Integer store;

    @Option(names = "--server", description = "Number of Server nodes")
    private Integer server;

    @Option(names = "--graph", description = "Graph name")
    private String graph;

    @Option(names = "--timeout", description = "Timeout in milliseconds")
    private Long timeout;

    @Option(names = "--interactive", description = "Interactive mode")
    private boolean interactive;

    @Override
    public void run() {
        if (config != null) {
            System.setProperty("cluster.config", config);
        }
        if (pd != null) {
            System.setProperty("cluster.pd", String.valueOf(pd));
        }
        if (store != null) {
            System.setProperty("cluster.store", String.valueOf(store));
        }
        if (server != null) {
            System.setProperty("cluster.server", String.valueOf(server));
        }
        if (timeout != null) {
            System.setProperty("cluster.timeout", String.valueOf(timeout));
        }

        ClusterTestConfig cfg = ClusterTestConfig.load();
        if (graph != null) {
            cfg.setGraphName(graph);
        }

        HugeGraphCluster cluster = new HugeGraphCluster(
                cfg.getPd(), cfg.getStore(), cfg.getServer(), cfg.getGraphName());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down cluster...");
            cluster.stop();
        }));

        System.out.println("Starting cluster: pd=" + cfg.getPd() +
                            " store=" + cfg.getStore() +
                            " server=" + cfg.getServer());
        cluster.start();
        System.out.println("Cluster started successfully.");

        if (interactive) {
            runInteractive(cluster);
        } else {
            waitForShutdown();
        }

        cluster.stop();
        System.out.println("Cluster stopped.");
    }

    private void runInteractive(HugeGraphCluster cluster) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("> ");
            try {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                switch (line) {
                    case "status":
                        System.out.println("PD: " + cluster.getAlivePDNodeCount() +
                                           ", Store: " + cluster.getAliveStoreNodeCount() +
                                           ", Server: " + cluster.getAliveServerNodeCount());
                                        break;
                    case "add":
                        int idx = cluster.addServerNode();
                        System.out.println("Added server node, index=" + idx);
                        break;
                    case "remove":
                        int count = cluster.getAliveServerNodeCount();
                        if (count > 1) {
                            cluster.removeServerNode(count - 1);
                            System.out.println("Removed server node, index=" + (count - 1));
                        } else {
                            System.out.println("Cannot remove last server node");
                        }
                        break;
                    case "stop":
                        cluster.stop();
                        System.out.println("Cluster stopped.");
                        return;
                    case "exit":
                        return;
                    default:
                        System.out.println("Commands: status, add, remove, stop, exit");
                }
            } catch (IOException e) {
                break;
            }
        }
    }

    private void waitForShutdown() {
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(latch::countDown));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ClusterLauncher()).execute(args));
    }
}
