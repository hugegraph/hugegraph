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

package org.apache.hugegraph.ct.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;

import com.sun.net.httpserver.HttpServer;

public class MiniStoreProxyMain {

    public static void main(String[] args) throws Exception {
        int grpcPort = 0;
        int restPort = 0;
        String pdAddress = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--grpc-port":
                    grpcPort = Integer.parseInt(args[++i]);
                    break;
                case "--rest-port":
                    restPort = Integer.parseInt(args[++i]);
                    break;
                case "--pd-address":
                    pdAddress = args[++i];
                    break;
                default:
                    break;
            }
        }

        if (grpcPort <= 0 || restPort <= 0) {
            System.err.println("Usage: MiniStoreProxyMain --grpc-port <port> --rest-port <port> [--pd-address <addr>]");
            System.exit(1);
        }

        // Capture as final for lambda access
        final int finalGrpcPort = grpcPort;
        final int finalRestPort = restPort;
        final String finalPdAddress = pdAddress;

        ServerSocket grpcSocket = new ServerSocket(grpcPort);

        HttpServer httpServer = HttpServer.create(new InetSocketAddress(restPort), 0);
        httpServer.createContext("/actuator/health", exchange -> {
            String response = "{\"status\":\"UP\"}";
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.getBytes().length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });
        httpServer.setExecutor(null);
        httpServer.start();

        if (finalPdAddress != null && !finalPdAddress.isEmpty()) {
            Thread heartbeatThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        sendHeartbeat(finalPdAddress, finalGrpcPort, finalRestPort);
                        Thread.sleep(3000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            });
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
        }

        System.out.println("MiniStoreProxy started.");

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.stop(0);
            try {
                grpcSocket.close();
            } catch (IOException ignored) {
            }
            shutdownLatch.countDown();
        }));
        shutdownLatch.await();
    }

    private static void sendHeartbeat(String pdAddress, int grpcPort, int restPort) throws IOException {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("http://" + pdAddress + "/pd/store/heartbeat");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            String body = "{\"grpcAddress\":\"127.0.0.1:" + grpcPort +
                          "\",\"restAddress\":\"127.0.0.1:" + restPort + "\"}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes());
            }
            conn.getResponseCode();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
