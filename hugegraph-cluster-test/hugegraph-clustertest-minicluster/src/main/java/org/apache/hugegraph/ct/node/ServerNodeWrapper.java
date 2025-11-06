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

import static org.apache.hugegraph.ct.base.ClusterConstant.CONF_DIR;
import static org.apache.hugegraph.ct.base.ClusterConstant.EMPTY_SAMPLE_GROOVY_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.EXAMPLE_GROOVY_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.EXT_DIR;
import static org.apache.hugegraph.ct.base.ClusterConstant.GREMLIN_DRIVER_SETTING_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.GREMLIN_SERVER_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.JAVA_CMD;
import static org.apache.hugegraph.ct.base.ClusterConstant.LIB_DIR;
import static org.apache.hugegraph.ct.base.ClusterConstant.LOG4J_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.PLUGINS_DIR;
import static org.apache.hugegraph.ct.base.ClusterConstant.REMOTE_OBJECTS_SETTING_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.REMOTE_SETTING_FILE;
import static org.apache.hugegraph.ct.base.ClusterConstant.SERVER_LIB_PATH;
import static org.apache.hugegraph.ct.base.ClusterConstant.SERVER_PACKAGE_PATH;
import static org.apache.hugegraph.ct.base.ClusterConstant.SERVER_TEMPLATE_PATH;
import static org.apache.hugegraph.ct.base.ClusterConstant.isJava11OrHigher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ServerNodeWrapper extends AbstractNodeWrapper {

    public ServerNodeWrapper(int clusterIndex, int index) {
        super(clusterIndex, index);
        this.fileNames = new ArrayList<>(
                List.of(LOG4J_FILE, GREMLIN_SERVER_FILE, GREMLIN_DRIVER_SETTING_FILE,
                        REMOTE_SETTING_FILE, REMOTE_OBJECTS_SETTING_FILE));
        this.workPath = SERVER_LIB_PATH;
        createNodeDir(Paths.get(SERVER_TEMPLATE_PATH), getNodePath() + CONF_DIR + File.separator);
        this.fileNames = new ArrayList<>(List.of(EMPTY_SAMPLE_GROOVY_FILE, EXAMPLE_GROOVY_FILE));
        this.startLine = "INFO: [HttpServer] Started.";
        createNodeDir(Paths.get(SERVER_PACKAGE_PATH), getNodePath());
        createLogDir();
    }

    private static void addJarsToClasspath(File directory, List<String> classpath) {
        if (directory.exists() && directory.isDirectory()) {
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".jar"));
            if (files != null) {
                for (File file : files) {
                    classpath.add(file.getAbsolutePath());
                }
            }
        }
    }

    private static void addCpJarsToClasspath(File directory, List<String> classpath) {
        // Add jar starts with hugegraph in proper order
        String path = directory.getAbsolutePath();
        String[] jars = {"hugegraph-api-1.7.0.jar", "hugegraph-cassandra-1.7.0.jar", "hugegraph" +
                                                                                     "-common-1.7" +
                                                                                     ".0.jar",
                         "hugegraph-core-1.7.0.jar", "hugegraph-dist-1.7.0.jar",
                         "hugegraph-hbase-1" +
                         ".7.0.jar",
                         "hugegraph-hstore-1.7.0.jar", "hugegraph-mysql-1.7.0.jar",
                         "hugegraph-palo" +
                         "-1.7.0.jar",
                         "hugegraph-postgresql-1.7.0.jar", "hugegraph-rocksdb-1.7.0.jar",
                         "hugegraph-rpc-1.7.0.jar", "hugegraph-scylladb-1.7.0.jar",
                         "hugegraph-struct-1.7.0.jar"};
        for (String jar : jars) {
            classpath.add(path + File.separator + jar);
        }
        if (directory.exists() && directory.isDirectory()) {
            File[] files =
                    directory.listFiles((dir, name) -> name.endsWith(".jar") && !name.contains(
                            "hugegraph"));
            if (files != null) {
                for (File file : files) {
                    classpath.add(file.getAbsolutePath());
                }
            }
        }
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

            List<String> classpath = new ArrayList<>();
            addCpJarsToClasspath(new File(workPath + LIB_DIR), classpath);
            addJarsToClasspath(new File(workPath + EXT_DIR), classpath);
            addJarsToClasspath(new File(workPath + PLUGINS_DIR), classpath);
            String storeClassPath = String.join(":", classpath);

            startCmd.addAll(Arrays.asList(
                    "-Dname=HugeGraphServer" + this.index,
                    "--add-exports=java.base/jdk.internal.reflect=ALL-UNNAMED",
                    "-cp", storeClassPath,
                    "org.apache.hugegraph.dist.HugeGraphServer",
                    "./conf/gremlin-server.yaml",
                    "./conf/rest-server.properties"));
            ProcessBuilder processBuilder = runCmd(startCmd, stdoutFile);
            this.instance = processBuilder.start();
        } catch (IOException ex) {
            throw new AssertionError("Started server node failed. " + ex);
        }
    }

    @Override
    public String getID() {
        return "Server" + this.index;
    }
}
