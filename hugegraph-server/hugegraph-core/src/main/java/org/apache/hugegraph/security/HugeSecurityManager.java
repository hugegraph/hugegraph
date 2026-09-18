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

package org.apache.hugegraph.security;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.security.Permission;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class HugeSecurityManager extends SecurityManager {

    private static final String USER_DIR = System.getProperty("user.dir");

    private static final String USER_DIR_IDE = USER_DIR.endsWith("hugegraph-dist") ?
                                               USER_DIR.substring(0, USER_DIR.length() - 15) : null;

    private static final String GREMLIN_SERVER_WORKER = "gremlin-server-exec";
    private static final String TASK_WORKER = "task-worker";
    private static final Set<String> GREMLIN_EXECUTOR_CLASS = ImmutableSet.of(
            "org.apache.tinkerpop.gremlin.groovy.jsr223.GremlinGroovyScriptEngine",
            "org.apache.hugegraph.security.script.PolicyScriptEngine",
            "HugeGraphPolicyScript"
    );

    // TODO: add "suppressAccessChecks" (influence groovy-AST init now)
    private static final Set<String> DENIED_PERMISSIONS = ImmutableSet.of("setSecurityManager");

    private static final Set<String> ACCEPT_CLASS_LOADERS = ImmutableSet.of(
            "groovy.lang.GroovyClassLoader",
            "jdk.internal.reflect.DelegatingClassLoader",
            "org.codehaus.groovy.reflection.SunClassLoader",
            "org.codehaus.groovy.runtime.callsite.CallSiteClassLoader",
            "org.apache.hadoop.hbase.util.DynamicClassLoader",
            "org.apache.tinkerpop.gremlin.groovy.loaders.GremlinLoader"
    );

    private static final Set<String> CAFFEINE_CLASSES = ImmutableSet.of(
            "com.github.benmanes.caffeine.cache.BoundedLocalCache"
    );

    private static final Set<String> WHITE_SYSTEM_PROPERTIES = ImmutableSet.of(
            "line.separator",
            "file.separator",
            // Sofa
            "java.specification.version"
    );

    private static final Map<String, Set<String>> ASYNC_TASKS = ImmutableMap.of(
            // Fixed https://github.com/apache/hugegraph/pull/892#issue-387202362
            "org.apache.hugegraph.backend.tx.SchemaTransaction",
            ImmutableSet.of("removeVertexLabel", "removeEdgeLabel",
                            "removeIndexLabel", "rebuildIndex"),
            "org.apache.hugegraph.backend.tx.GraphIndexTransaction",
            ImmutableSet.of("asyncRemoveIndexLeft")
    );

    private static final Map<String, Set<String>> BACKEND_SNAPSHOT = ImmutableMap.of(
            "org.apache.hugegraph.backend.store.AbstractBackendStoreProvider",
            ImmutableSet.of("createSnapshot", "resumeSnapshot"),
            "org.apache.hugegraph.backend.store.raft.RaftBackendStoreProvider",
            ImmutableSet.of("createSnapshot", "resumeSnapshot")
    );

    private static final Set<String> HBASE_CLASSES = ImmutableSet.of(
            // Fixed #758
            "org.apache.hugegraph.backend.store.hbase.HbaseStore",
            "org.apache.hugegraph.backend.store.hbase.HbaseStore$HbaseSchemaStore",
            "org.apache.hugegraph.backend.store.hbase.HbaseStore$HbaseGraphStore",
            "org.apache.hugegraph.backend.store.hbase.HbaseSessions$RowIterator"
    );

    private static final Set<String> RAFT_CLASSES = ImmutableSet.of(
            "org.apache.hugegraph.backend.store.raft.RaftNode",
            "org.apache.hugegraph.backend.store.raft.StoreStateMachine",
            "org.apache.hugegraph.backend.store.raft.rpc.RpcForwarder"
    );

    private static final Set<String> SOFA_RPC_CLASSES = ImmutableSet.of(
            "com.alipay.sofa.rpc.tracer.sofatracer.RpcSofaTracer",
            "com.alipay.sofa.rpc.client.AbstractCluster"
    );

    private static final Map<String, Set<String>> NEW_SECURITY_EXCEPTION = ImmutableMap.of(
            "org.apache.hugegraph.security.HugeSecurityManager",
            ImmutableSet.of("newSecurityException")
    );

    private static final Set<String> IGNORE_CHECKED_CLASSES = new CopyOnWriteArraySet<>();
    private static final AtomicInteger STACK_CAPTURES = new AtomicInteger();
    private static final ThreadLocal<Integer> STACK_DEPTH = new ThreadLocal<>();
    private static final ThreadLocal<StackTraceElement[][]> STACK_FRAMES = new ThreadLocal<>();

    public static void ignoreCheckedClass(String clazz) {
        if (callFromGremlin()) {
            throw newSecurityException("Not allowed to add ignore check via Gremlin");
        }
        IGNORE_CHECKED_CLASSES.add(clazz);
    }

    @Override
    public void checkPermission(Permission perm) {
        enterStackScope();
        try {
            if (DENIED_PERMISSIONS.contains(perm.getName()) && callFromGremlin()) {
                // TODO: consider ban the Reflection/Runtime/SerializablePermission after
                //       identifying the "callFromGremlin()" clearly
                throw newSecurityException("Not allowed to access denied permission via Gremlin: %s",
                                           perm);
            }
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
        this.checkPermission(perm);
        // Ignore the context & enable when needed
        //super.checkPermission(perm, context);
    }

    @Override
    public void checkCreateClassLoader() {
        enterStackScope();
        try {
            if (!callFromAcceptClassLoaders() && callFromGremlin()) {
                throw newSecurityException("Not allowed to create class loader via Gremlin");
            }
            super.checkCreateClassLoader();
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkLink(String lib) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to link library via Gremlin");
            }
            super.checkLink(lib);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkAccess(Thread thread) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromCaffeine() &&
                !callFromAsyncTasks() && !callFromEventHubNotify() &&
                !callFromBackendHbase() &&
                !callFromRaft() && !callFromSofaRpc() && !callFromIgnoreCheckedClass()) {
                throw newSecurityException("Not allowed to access thread via Gremlin");
            }
            super.checkAccess(thread);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkAccess(ThreadGroup threadGroup) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromCaffeine() &&
                !callFromAsyncTasks() && !callFromEventHubNotify() &&
                !callFromBackendHbase() &&
                !callFromRaft() && !callFromSofaRpc() &&
                !callFromIgnoreCheckedClass()) {
                throw newSecurityException("Not allowed to access thread group via Gremlin");
            }
            super.checkAccess(threadGroup);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkExit(int status) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to call System.exit() via Gremlin");
            }
            super.checkExit(status);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkExec(String cmd) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to execute command via Gremlin");
            }
            super.checkExec(cmd);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkRead(FileDescriptor fd) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to read fd via Gremlin");
            }
            super.checkRead(fd);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkRead(String file) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromCaffeine() &&
                !readGroovyInCurrentDir(file) && !callFromBackendHbase() &&
                !callFromSnapshot() && !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to read file via Gremlin: %s", file);
            }
            super.checkRead(file);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkRead(String file, Object context) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to read file via Gremlin: %s", file);
            }
            super.checkRead(file, context);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkWrite(FileDescriptor fd) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to write fd via Gremlin");
            }
            super.checkWrite(fd);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkWrite(String file) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromSnapshot() && !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to write file via Gremlin");
            }
            super.checkWrite(file);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkDelete(String file) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromSnapshot()) {
                throw newSecurityException("Not allowed to delete file via Gremlin");
            }
            super.checkDelete(file);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkListen(int port) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to listen socket via Gremlin");
            }
            super.checkListen(port);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkAccept(String host, int port) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to accept socket via Gremlin");
            }
            super.checkAccept(host, port);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkConnect(String host, int port) {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromBackendHbase() &&
                !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to connect socket via Gremlin");
            }
            super.checkConnect(host, port);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to connect socket via Gremlin");
            }
            super.checkConnect(host, port, context);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkMulticast(InetAddress addrs) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to multicast via Gremlin");
            }
            super.checkMulticast(addrs);
        } finally {
            exitStackScope();
        }
    }

    public void checkMemberAccess(Class<?> clazz, int which) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to access member via Gremlin");
            }
        } finally {
            exitStackScope();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void checkMulticast(InetAddress addrs, byte ttl) {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to multicast via Gremlin");
            }
            super.checkMulticast(addrs, ttl);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkSetFactory() {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to set socket factory via Gremlin");
            }
            super.checkSetFactory();
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkPropertiesAccess() {
        enterStackScope();
        try {
            if (callFromGremlin() && !callFromSofaRpc() && !callFromNewSecurityException()) {
                throw newSecurityException("Not allowed to access system properties via Gremlin");
            }
            super.checkPropertiesAccess();
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkPropertyAccess(String key) {
        enterStackScope();
        try {
            if (!callFromAcceptClassLoaders() && callFromGremlin() &&
                !WHITE_SYSTEM_PROPERTIES.contains(key) && !callFromBackendHbase() &&
                !callFromSnapshot() && !callFromRaft() && !callFromSofaRpc()) {
                throw newSecurityException("Not allowed to access system property(%s) via Gremlin",
                                           key);
            }
            super.checkPropertyAccess(key);
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkPrintJobAccess() {
        enterStackScope();
        try {
            if (callFromGremlin()) {
                throw newSecurityException("Not allowed to print job via Gremlin");
            }
            super.checkPrintJobAccess();
        } finally {
            exitStackScope();
        }
    }

    @Override
    public void checkPackageAccess(String pkg) {
        // TODO: consider ban the "*.reflect" package after identifying "callFromGremlin()" clearly
        //       maybe better than check in "checkPermission()" (early check & better performance)
        super.checkPackageAccess(pkg);
    }

    @Override
    public void checkPackageDefinition(String pkg) {
        super.checkPackageDefinition(pkg);
    }

    @Override
    public void checkSecurityAccess(String target) {
        super.checkSecurityAccess(target);
    }

    private static SecurityException newSecurityException(String message, Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        /*
         * Use dynamic logger here because "static final logger" can't be initialized:
         * the logger is not initialized when HugeSecurityManager class is loaded
         */
        Logger log = Log.logger(HugeSecurityManager.class);
        log.warn("SecurityException: {}", message);
        return new SecurityException(message);
    }

    private static boolean readGroovyInCurrentDir(String file) {
        return file != null && (USER_DIR != null && file.startsWith(USER_DIR) ||
                                USER_DIR_IDE != null && file.startsWith(USER_DIR_IDE)) &&
               (file.endsWith(".class") || file.endsWith(".groovy"));
    }

    // TODO: add/use more accurate flag to identify the caller -> callFromUserGremlin()
    private static boolean callFromGremlin() {
        // Currently, the lifecycle of GremlinExecutor is not clear(too broad)
        return callFromWorkerWithClass(GREMLIN_EXECUTOR_CLASS);
    }

    private static boolean callFromAcceptClassLoaders() {
        return callFromWorkerWithClass(ACCEPT_CLASS_LOADERS);
    }

    private static boolean callFromCaffeine() {
        return callFromWorkerWithClass(CAFFEINE_CLASSES);
    }

    private static boolean callFromEventHubNotify() {
        // Fixed issue #758
        // notify() will create thread when submit a task to executor
        return callFromMethod("org.apache.hugegraph.event.EventHub", "notify");
    }

    private static boolean callFromAsyncTasks() {
        // Async tasks will create thread when submitted to executor
        return callFromMethods(ASYNC_TASKS);
    }

    private static boolean callFromBackendHbase() {
        // TODO: remove this unsafe entrance
        return callFromWorkerWithClass(HBASE_CLASSES);
    }

    private static boolean callFromSnapshot() {
        return callFromMethods(BACKEND_SNAPSHOT);
    }

    private static boolean callFromRaft() {
        return callFromWorkerWithClass(RAFT_CLASSES);
    }

    private static boolean callFromSofaRpc() {
        return callFromWorkerWithClass(SOFA_RPC_CLASSES);
    }

    private static boolean callFromNewSecurityException() {
        return callFromMethods(NEW_SECURITY_EXCEPTION);
    }

    private static boolean callFromIgnoreCheckedClass() {
        return callFromWorkerWithClass(IGNORE_CHECKED_CLASSES);
    }

    private static boolean callFromWorkerWithClass(Set<String> classes) {
        Thread curThread = Thread.currentThread();
        if (curThread.getName().startsWith(GREMLIN_SERVER_WORKER) ||
            curThread.getName().startsWith(TASK_WORKER)) {
            StackTraceElement[] elements = currentStack();
            for (StackTraceElement element : elements) {
                String className = element.getClassName();
                if (classes.contains(className) ||
                    (classes == GREMLIN_EXECUTOR_CLASS &&
                     (className.equals("HugeGraphPolicyScript") ||
                      className.endsWith(".HugeGraphPolicyScript") ||
                      className.startsWith("HugeGraphPolicyScript$_") ||
                      className.contains(".HugeGraphPolicyScript$_")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean callFromMethods(Map<String, Set<String>> methods) {
        StackTraceElement[] elements = currentStack();
        for (StackTraceElement element : elements) {
            Set<String> clazzMethods = methods.get(element.getClassName());
            if (clazzMethods != null &&
                clazzMethods.contains(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean callFromMethod(String clazz, String method) {
        StackTraceElement[] elements = currentStack();
        for (StackTraceElement element : elements) {
            if (clazz.equals(element.getClassName()) &&
                method.equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    static void resetStackCapturesForTest() {
        STACK_CAPTURES.set(0);
    }

    static int stackCapturesForTest() {
        return STACK_CAPTURES.get();
    }

    static int captureCountRunningAllHelpers() {
        resetStackCapturesForTest();
        enterStackScope();
        try {
            callFromGremlin();
            callFromCaffeine();
            callFromAsyncTasks();
            callFromEventHubNotify();
            callFromBackendHbase();
            callFromRaft();
            callFromSofaRpc();
            callFromIgnoreCheckedClass();
            callFromSnapshot();
            return stackCapturesForTest();
        } finally {
            exitStackScope();
        }
    }

    private static void enterStackScope() {
        Integer depth = STACK_DEPTH.get();
        int next = depth == null ? 0 : depth;
        StackTraceElement[][] frames = STACK_FRAMES.get();
        if (frames == null) {
            frames = new StackTraceElement[4][];
            STACK_FRAMES.set(frames);
        }
        if (next == frames.length) {
            frames = Arrays.copyOf(frames, frames.length * 2);
            STACK_FRAMES.set(frames);
        }
        frames[next] = null;
        STACK_DEPTH.set(next + 1);
    }

    private static void exitStackScope() {
        int depth = STACK_DEPTH.get() - 1;
        STACK_FRAMES.get()[depth] = null;
        if (depth == 0) {
            STACK_DEPTH.remove();
            STACK_FRAMES.remove();
        } else {
            STACK_DEPTH.set(depth);
        }
    }

    private static StackTraceElement[] currentStack() {
        Integer depth = STACK_DEPTH.get();
        if (depth == null || depth == 0) {
            return Thread.currentThread().getStackTrace();
        }
        StackTraceElement[][] frames = STACK_FRAMES.get();
        int index = depth - 1;
        if (frames[index] == null) {
            frames[index] = Thread.currentThread().getStackTrace();
            STACK_CAPTURES.incrementAndGet();
        }
        return frames[index];
    }

    private static void filterBasicSensitiveClasses() {
        // TODO: Conflicts with log4j2, handle it in 1.5.0
        //Reflection.registerFieldsToFilter(Thread.class, "name");
        //Reflection.registerMethodsToFilter(Class.class, "forName", "newInstance");
        //Reflection.registerMethodsToFilter(ClassLoader.class, "loadClass", "newInstance");
        //Reflection.registerMethodsToFilter(Method.class, "invoke", "setAccessible");
        //Reflection.registerMethodsToFilter(Field.class, "set", "setAccessible");
        //Reflection.registerMethodsToFilter(java.lang.reflect.Constructor.class, "newInstance",
        //                                   "setAccessible");
        //Reflection.registerMethodsToFilter(Runtime.class, "exec", "getRuntime");
        //Reflection.registerMethodsToFilter(ProcessBuilder.class, "command", "start",
        //                                   "startPipeline");
        //Reflection.registerMethodsToFilter(Reflection.loadClass("java.lang.ProcessImpl"),
        //                                   "forkAndExec", "setAccessible", "start");
    }
}
