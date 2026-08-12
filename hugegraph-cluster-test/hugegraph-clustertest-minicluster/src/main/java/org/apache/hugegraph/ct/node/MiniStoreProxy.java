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

import static org.apache.hugegraph.ct.base.ClusterConstant.CT_PACKAGE_PATH;
import static org.apache.hugegraph.ct.base.ClusterConstant.JAVA_CMD;
import static org.apache.hugegraph.ct.base.ClusterConstant.isJava11OrHigher;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MiniStoreProxy extends AbstractNodeWrapper {

    private final int grpcPort;
    private final int restPort;
    private String pdAddress;

    public MiniStoreProxy(int clusterIndex, int index, int grpcPort, int restPort) {
        super(clusterIndex, index);
        this.grpcPort = grpcPort;
        this.restPort = restPort;
        this.startLine = "MiniStoreProxy started.";
        this.workPath = CT_PACKAGE_PATH;
        createLogDir();
    }

    public void setPdAddress(String pdAddress) {
        this.pdAddress = pdAddress;
    }

    @Override
    public void start() {
        try {
            File stdoutFile = new File(getLogPath());
            List<String> startCmd = new ArrayList<>();
            startCmd.add(JAVA_CMD);
            if (!isJava11OrHigher()) {
                LOG.error("Please make sure that the JDK is installed and the version >= 11");
                return;
            }

            String classpath = System.getProperty("java.class.path");
            startCmd.addAll(List.of(
                    "--add-modules", "jdk.httpserver",
                    "-Xms32m",
                    "-Xmx64m",
                    "-XX:+UseSerialGC",
                    "-cp", classpath,
                    MiniStoreProxyMain.class.getName(),
                    "--grpc-port", String.valueOf(grpcPort),
                    "--rest-port", String.valueOf(restPort)));

            if (pdAddress != null && !pdAddress.isEmpty()) {
                startCmd.addAll(List.of("--pd-address", pdAddress));
            }

            ProcessBuilder processBuilder = runCmd(startCmd, stdoutFile);
            this.instance = processBuilder.start();
        } catch (IOException ex) {
            throw new AssertionError("Start MiniStoreProxy failed. " + ex);
        }
    }

    @Override
    public String getID() {
        return "MiniStore" + this.index;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public int getRestPort() {
        return restPort;
    }
}
