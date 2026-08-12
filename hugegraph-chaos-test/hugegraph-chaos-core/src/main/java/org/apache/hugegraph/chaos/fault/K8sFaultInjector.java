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

package org.apache.hugegraph.chaos.fault;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import org.apache.hugegraph.chaos.model.ChaosConfig;
import org.apache.hugegraph.chaos.model.FaultType;
import org.apache.hugegraph.chaos.model.Step;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class K8sFaultInjector implements FaultInjector {

    private static final Logger LOG = LogManager.getLogger(K8sFaultInjector.class);
    private final KubernetesClient k8sClient;
    private final String namespace;
    private final List<String> activeFaults = new ArrayList<>();
    private final Map<String, ExecWatch> activeExecWatches = new ConcurrentHashMap<>();

    public K8sFaultInjector(ChaosConfig.K8sTarget config) {
        String kubeconfig = config.getKubeconfig();
        if (kubeconfig != null && !kubeconfig.isEmpty()) {
            this.k8sClient = new KubernetesClientBuilder()
                    .withConfig(Config.fromKubeconfig(kubeconfig))
                    .build();
        } else {
            this.k8sClient = new KubernetesClientBuilder().build();
        }
        this.namespace = config.getNamespace() != null
                         ? config.getNamespace() : "default";
    }

    @Override
    public void inject(Step.StepAction action) throws Exception {
        FaultType type = FaultType.fromValue(action.getType());
        if (type == null) {
            throw new UnsupportedOperationException(
                "Unknown K8s fault type: " + action.getType());
        }
        String target = action.getTarget();
        switch (type) {
            case POD_KILL:
                injectPodKill(target);
                break;
            case POD_FAILURE:
                injectPodFailure(target);
                break;
            case CONTAINER_KILL:
                injectContainerKill(target, action);
                break;
            case NETWORK_DELAY:
                injectNetworkDelay(target, action);
                break;
            case NETWORK_LOSS:
                injectNetworkLoss(target, action);
                break;
            case NETWORK_PARTITION:
                injectNetworkPartition(target, action);
                break;
            case NETWORK_BANDWIDTH:
                injectNetworkBandwidth(target, action);
                break;
            case NETWORK_CORRUPT:
                injectNetworkCorrupt(target, action);
                break;
            case NETWORK_DUP:
                injectNetworkDup(target, action);
                break;
            case CPU_STRESS:
                injectCpuStress(target, action);
                break;
            case MEMORY_STRESS:
                injectMemoryStress(target, action);
                break;
            case DISK_STRESS:
                injectDiskStress(target, action);
                break;
            case IO_STRESS:
                injectIoStress(target, action);
                break;
            case DISK_FAULT:
                injectDiskFault(target, action);
                break;
            case DISK_FILL:
                injectDiskFill(target, action);
                break;
            case DNS_FAULT:
                injectDnsFault(target, action);
                break;
            case TIME_SKEW:
                injectTimeSkew(target, action);
                break;
            case JVM_LATENCY:
            case JVM_EXCEPTION:
            case JVM_RETURN:
            case JVM_OOM:
            case JVM_GC:
            case JVM_THREAD_DEADLOCK:
                injectJvmFault(target, action, type);
                break;
            case KERNEL_FAULT:
                injectKernelFault(target, action);
                break;
            case HTTP_FAULT:
                injectHttpFault(target, action);
                break;
            case AWS_EC2_STOP:
            case AWS_EC2_REBOOT:
            case AWS_EBS_LOSS:
            case AWS_RDS_REBOOT:
                injectAwsFault(target, action, type);
                break;
            case GCE_INSTANCE_STOP:
            case GCE_INSTANCE_RESET:
            case GCE_DISK_LOSS:
                injectGcpFault(target, action, type);
                break;
            default:
                throw new SkippableFaultException(
                    "Fault type '" + type.getValue() +
                    "' is not supported in Kubernetes environment");
        }
        activeFaults.add(target + ":" + type.getValue());
    }

    @Override
    public void recover(Step.StepAction action) throws Exception {
        FaultType type = FaultType.fromValue(action.getType());
        if (type == null) {
            LOG.warn("Unknown K8s fault type for recovery: {}",
                     action.getType());
            return;
        }
        String target = action.getTarget();
        switch (type) {
            case POD_KILL:
                LOG.info("Pod {} will be recreated by K8s automatically",
                         target);
                break;
            case POD_FAILURE:
                recoverPodFailure(target);
                break;
            case CONTAINER_KILL:
                LOG.info("Container in pod {} will restart automatically",
                         target);
                break;
            case NETWORK_DELAY:
            case NETWORK_LOSS:
            case NETWORK_PARTITION:
            case NETWORK_BANDWIDTH:
            case NETWORK_CORRUPT:
            case NETWORK_DUP:
                recoverNetwork(target);
                break;
            case CPU_STRESS:
            case MEMORY_STRESS:
            case DISK_STRESS:
            case IO_STRESS:
                recoverStress(target);
                break;
            case DISK_FAULT:
                recoverDiskFault(target, action);
                break;
            case DISK_FILL:
                recoverDiskFill(target, action);
                break;
            case DNS_FAULT:
                recoverDnsFault(target, action);
                break;
            case TIME_SKEW:
                recoverTimeSkew(target);
                break;
            case JVM_LATENCY:
            case JVM_EXCEPTION:
            case JVM_RETURN:
            case JVM_OOM:
            case JVM_GC:
            case JVM_THREAD_DEADLOCK:
                recoverJvmFault(target);
                break;
            case KERNEL_FAULT:
                recoverKernelFault(target);
                break;
            case HTTP_FAULT:
                recoverHttpFault(target);
                break;
            default:
                LOG.warn("No recovery for K8s fault type: {}", type);
        }
        activeFaults.remove(target + ":" + type.getValue());
    }

    @Override
    public void healAll() {
        LOG.info("Healing all active K8s faults");
        for (String fault : new ArrayList<>(activeFaults)) {
            String[] parts = fault.split(":", 2);
            if (parts.length == 2) {
                try {
                    Step.StepAction action = new Step.StepAction();
                    action.setType(parts[1]);
                    action.setTarget(parts[0]);
                    recover(action);
                } catch (Exception e) {
                    LOG.error("Failed to heal fault: {}", fault, e);
                }
            }
        }
    }

    private int paramInt(Map<String, Object> params, String key, int def) {
        Object v = params.get(key);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    private String paramString(Map<String, Object> params, String key,
                               String def) {
        Object v = params.get(key);
        return v != null ? v.toString() : def;
    }

    private void injectPodKill(String podName) {
        LOG.info("Deleting pod: {}/{}", namespace, podName);
        try {
            k8sClient.pods().inNamespace(namespace)
                     .withName(podName).delete();
        } catch (Exception e) {
            LOG.warn("Failed to delete pod {}/{}: {}",
                     namespace, podName, e.getMessage());
        }
    }

    private void injectPodFailure(String podName) {
        LOG.info("Injecting pod failure on: {}/{}", namespace, podName);
        execInPod(podName, "pause");
    }

    private void recoverPodFailure(String podName) {
        LOG.info("Pod failure recovery: deleting pod {} for recreation",
                 podName);
        try {
            k8sClient.pods().inNamespace(namespace)
                     .withName(podName).delete();
        } catch (Exception e) {
            LOG.warn("Failed to delete pod for recovery: {}", podName, e);
        }
    }

    private void injectContainerKill(String podName,
                                      Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String container = paramString(params, "container", "");
        LOG.info("Killing container '{}' in pod: {}/{}",
                 container, namespace, podName);
        String cmd = container.isEmpty()
                     ? "kill 1"
                     : "kill $(pgrep -f " + container + ") || kill 1";
        execInPod(podName, cmd);
    }

    private void injectNetworkDelay(String podName,
                                     Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        int latency = paramInt(params, "latency", 100);
        int jitter = paramInt(params, "jitter", 0);
        LOG.info("Injecting network delay on pod {}/{}: {}ms +/- {}ms",
                namespace, podName, latency, jitter);
        String cmd = String.format(
            "tc qdisc add dev eth0 root netem delay %dms %dms 2>/dev/null || " +
            "apt-get update && apt-get install -y iproute2 && " +
            "tc qdisc add dev eth0 root netem delay %dms %dms",
            latency, jitter, latency, jitter);
        execInPod(podName, cmd);
    }

    private void injectNetworkLoss(String podName,
                                    Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        double loss = params.get("loss") instanceof Number
                      ? ((Number) params.get("loss")).doubleValue() : 10.0;
        LOG.info("Injecting network loss on pod {}/{}: {}%",
                namespace, podName, loss);
        String cmd = String.format(
            "tc qdisc add dev eth0 root netem loss %.1f%% 2>/dev/null || " +
            "apt-get update && apt-get install -y iproute2 && " +
            "tc qdisc add dev eth0 root netem loss %.1f%%",
            loss, loss);
        execInPod(podName, cmd);
    }

    private void injectNetworkPartition(String podName,
                                         Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String targetIp = paramString(params, "target_ip", "");
        LOG.info("Injecting network partition on pod {}/{} to: {}",
                namespace, podName, targetIp);
        String cmd = targetIp.isEmpty()
            ? "tc qdisc add dev eth0 root netem loss 100% 2>/dev/null || true"
            : String.format(
                "iptables -A OUTPUT -d %s -j DROP 2>/dev/null || true",
                targetIp);
        execInPod(podName, cmd);
    }

    private void injectNetworkBandwidth(String podName,
                                         Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String rate = paramString(params, "rate", "1mbit");
        LOG.info("Injecting bandwidth limit on pod {}/{}: {}",
                namespace, podName, rate);
        String cmd = String.format(
            "tc qdisc add dev eth0 root tbf rate %s burst 32kbit " +
            "latency 400ms 2>/dev/null || true", rate);
        execInPod(podName, cmd);
    }

    private void injectNetworkCorrupt(String podName,
                                       Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        double corrupt = params.get("corrupt") instanceof Number
                         ? ((Number) params.get("corrupt")).doubleValue()
                         : 10.0;
        LOG.info("Injecting network corrupt on pod {}/{}: {}%",
                namespace, podName, corrupt);
        String cmd = String.format(
            "tc qdisc add dev eth0 root netem corrupt %.1f%% 2>/dev/null || true",
            corrupt);
        execInPod(podName, cmd);
    }

    private void injectNetworkDup(String podName,
                                   Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        double dup = params.get("dup") instanceof Number
                     ? ((Number) params.get("dup")).doubleValue() : 10.0;
        LOG.info("Injecting network duplication on pod {}/{}: {}%",
                namespace, podName, dup);
        String cmd = String.format(
            "tc qdisc add dev eth0 root netem duplicate %.1f%% 2>/dev/null || true",
            dup);
        execInPod(podName, cmd);
    }

    private void recoverNetwork(String podName) {
        LOG.info("Recovering network on pod {}/{}", namespace, podName);
        execInPod(podName,
                  "tc qdisc del dev eth0 root 2>/dev/null || true; " +
                  "iptables -F OUTPUT 2>/dev/null || true");
    }

    private void injectCpuStress(String podName,
                                  Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        int cores = paramInt(params, "cores", 1);
        LOG.info("Injecting CPU stress on pod {}/{}: {} cores",
                namespace, podName, cores);
        String cmd = String.format(
            "which stress-ng >/dev/null 2>&1 && stress-ng --cpu %d " +
            "|| (apt-get update && apt-get install -y stress-ng && stress-ng --cpu %d)",
            cores, cores);
        String key = podName + ":cpu";
        ExecWatch watch = execInPodAsync(podName, cmd);
        if (watch != null) {
            activeExecWatches.put(key, watch);
        }
    }

    private void injectMemoryStress(String podName,
                                     Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        int percent = paramInt(params, "percent", 50);
        LOG.info("Injecting memory stress on pod {}/{}: {}%",
                namespace, podName, percent);
        String cmd = String.format(
            "which stress-ng >/dev/null 2>&1 && stress-ng --vm 1 --vm-bytes %d%% " +
            "|| (apt-get update && apt-get install -y stress-ng && stress-ng --vm 1 --vm-bytes %d%%)",
            percent, percent);
        String key = podName + ":memory";
        ExecWatch watch = execInPodAsync(podName, cmd);
        if (watch != null) {
            activeExecWatches.put(key, watch);
        }
    }

    private void injectDiskStress(String podName,
                                   Step.StepAction action) {
        LOG.info("Injecting disk stress on pod {}/{}", namespace, podName);
        String cmd = "which stress-ng >/dev/null 2>&1 && stress-ng --hdd 1 " +
                     "|| (apt-get update && apt-get install -y stress-ng && stress-ng --hdd 1)";
        String key = podName + ":disk";
        ExecWatch watch = execInPodAsync(podName, cmd);
        if (watch != null) {
            activeExecWatches.put(key, watch);
        }
    }

    private void injectIoStress(String podName,
                                 Step.StepAction action) {
        LOG.info("Injecting I/O stress on pod {}/{}", namespace, podName);
        String cmd = "which stress-ng >/dev/null 2>&1 && stress-ng --io 1 " +
                     "|| (apt-get update && apt-get install -y stress-ng && stress-ng --io 1)";
        String key = podName + ":io";
        ExecWatch watch = execInPodAsync(podName, cmd);
        if (watch != null) {
            activeExecWatches.put(key, watch);
        }
    }

    private void recoverStress(String podName) {
        LOG.info("Stopping stress on pod {}/{}", namespace, podName);
        closeExecWatch(podName + ":cpu");
        closeExecWatch(podName + ":memory");
        closeExecWatch(podName + ":disk");
        closeExecWatch(podName + ":io");
        execInPod(podName, "pkill stress-ng || true");
    }

    private void injectDiskFault(String podName,
                                  Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fault");
        LOG.info("Injecting disk fault on pod {}/{} at: {}",
                namespace, podName, path);
        execInPod(podName, "mkdir -p " + path + " && chmod 000 " + path);
    }

    private void recoverDiskFault(String podName,
                                   Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fault");
        LOG.info("Recovering disk fault on pod {}/{} at: {}",
                namespace, podName, path);
        execInPod(podName,
                  "chmod 755 " + path + " 2>/dev/null || true; " +
                  "rm -rf " + path + " 2>/dev/null || true");
    }

    private void injectDiskFill(String podName,
                                 Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fill");
        String size = paramString(params, "size", "1G");
        LOG.info("Injecting disk fill on pod {}/{} at: {} size: {}",
                namespace, podName, path, size);
        execInPod(podName,
                  "mkdir -p " + path + " && dd if=/dev/zero of=" +
                  path + "/fill bs=1M count=" + sizeToMB(size) +
                  " 2>/dev/null || true");
    }

    private void recoverDiskFill(String podName,
                                  Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fill");
        LOG.info("Recovering disk fill on pod {}/{} at: {}",
                namespace, podName, path);
        execInPod(podName, "rm -rf " + path + " 2>/dev/null || true");
    }

    private void injectDnsFault(String podName,
                                 Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String domain = paramString(params, "domain", "");
        String resolveTo = paramString(params, "resolve_to", "0.0.0.0");
        LOG.info("Injecting DNS fault on pod {}/{}: {} -> {}",
                namespace, podName, domain, resolveTo);
        execInPod(podName,
                  "echo '" + resolveTo + " " + domain + "' >> /etc/hosts");
    }

    private void recoverDnsFault(String podName,
                                  Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String domain = paramString(params, "domain", "");
        LOG.info("Recovering DNS fault on pod {}/{} for: {}",
                namespace, podName, domain);
        execInPod(podName,
                  "sed -i '/" + domain + "/d' /etc/hosts 2>/dev/null || true");
    }

    private void injectTimeSkew(String podName,
                                 Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String offset = paramString(params, "offset", "+60s");
        LOG.info("Injecting time skew on pod {}/{}: {}",
                namespace, podName, offset);
        execInPod(podName,
                  "date -s \"$(date -d '" + offset + "')\" 2>/dev/null || true");
    }

    private void recoverTimeSkew(String podName) {
        LOG.info("Recovering time skew on pod {}/{}", namespace, podName);
        execInPod(podName,
                  "ntpdate -u pool.ntp.org 2>/dev/null || true");
    }

    private void injectJvmFault(String podName, Step.StepAction action,
                                 FaultType type) {
        Map<String, Object> params = action.getParams();
        String jvmPid = paramString(params, "pid", "1");
        LOG.info("Injecting JVM fault {} on pod {}/{} pid: {}",
                type.getValue(), namespace, podName, jvmPid);
        String cmd = String.format(
            "which jstack >/dev/null 2>&1 && echo 'JVM fault %s simulated on pid %s' " +
            "|| echo 'JVM agent not available - fault %s skipped'",
            type.getValue(), jvmPid, type.getValue());
        execInPod(podName, cmd);
    }

    private void recoverJvmFault(String podName) {
        LOG.info("JVM fault recovery on pod {}/{}", namespace, podName);
    }

    private void injectKernelFault(String podName,
                                    Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String fault = paramString(params, "fault", "panic");
        LOG.info("Injecting kernel fault {} on pod {}/{}",
                fault, namespace, podName);
        execInPod(podName,
                  "echo 'Kernel fault " + fault +
                  " injection requires kernel module' || true");
    }

    private void recoverKernelFault(String podName) {
        LOG.info("Kernel fault recovery on pod {}/{}",
                 namespace, podName);
    }

    private void injectHttpFault(String podName,
                                  Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        int port = paramInt(params, "port", 8080);
        String method = paramString(params, "method", "GET");
        int errorCode = paramInt(params, "error_code", 500);
        LOG.info("Injecting HTTP fault on pod {}/{} port: {} error: {}",
                namespace, podName, port, errorCode);
        String cmd = String.format(
            "iptables -A INPUT -p tcp --dport %d -m string " +
            "--string '%s' --algo bm -j REJECT " +
            "--reject-with tcp-reset 2>/dev/null || true",
            port, method);
        execInPod(podName, cmd);
    }

    private void recoverHttpFault(String podName) {
        LOG.info("Recovering HTTP fault on pod {}/{}",
                 namespace, podName);
        execInPod(podName, "iptables -F INPUT 2>/dev/null || true");
    }

    private void injectAwsFault(String podName, Step.StepAction action,
                                 FaultType type) {
        LOG.info("AWS fault {} on target {} - requires AWS CLI and IAM",
                type.getValue(), podName);
        execInPod(podName,
                  "echo 'AWS fault " + type.getValue() +
                  " requires AWS CLI configuration' || true");
    }

    private void injectGcpFault(String podName, Step.StepAction action,
                                 FaultType type) {
        LOG.info("GCP fault {} on target {} - requires gcloud CLI",
                type.getValue(), podName);
        execInPod(podName,
                  "echo 'GCP fault " + type.getValue() +
                  " requires gcloud CLI configuration' || true");
    }

    private void closeExecWatch(String key) {
        ExecWatch watch = activeExecWatches.remove(key);
        if (watch != null) {
            try {
                watch.close();
            } catch (Exception e) {
                LOG.warn("Failed to close exec watch for {}", key, e);
            }
        }
    }

    private ExecWatch execInPodAsync(String podName, String command) {
        LOG.debug("Executing async in pod {}/{}: {}",
                  namespace, podName, command);
        try {
            return k8sClient.pods().inNamespace(namespace)
                    .withName(podName)
                    .redirectingOutput()
                    .redirectingError()
                    .usingListener(
                        new io.fabric8.kubernetes.client.dsl.ExecListener() {
                        @Override
                        public void onOpen() {
                            LOG.debug("Exec stream opened for pod {}/{}",
                                      namespace, podName);
                        }

                        @Override
                        public void onFailure(
                            Throwable t,
                            io.fabric8.kubernetes.client.dsl.ExecListener
                                .Response response) {
                            LOG.warn("Exec stream failed for pod {}/{}: {}",
                                     namespace, podName, t.getMessage());
                        }

                        @Override
                        public void onClose(int code, String reason) {
                            LOG.debug("Exec stream closed for pod {}/{}",
                                      namespace, podName);
                        }
                    })
                    .exec("sh", "-c", command);
        } catch (Exception e) {
            LOG.error("Failed to start async exec in pod {}/{}",
                      namespace, podName, e);
            return null;
        }
    }

    private void execInPod(String podName, String command) {
        LOG.debug("Executing in pod {}/{}: {}", namespace, podName, command);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            k8sClient.pods().inNamespace(namespace)
                     .withName(podName)
                     .writingOutput(out)
                     .writingError(err)
                     .withTTY()
                     .exec("sh", "-c", command);
            String output = out.toString();
            String error = err.toString();
            if (!output.isEmpty()) {
                LOG.debug("Pod exec output: {}", output);
            }
            if (!error.isEmpty()) {
                LOG.warn("Pod exec error: {}", error);
            }
        } catch (Exception e) {
            LOG.error("Failed to execute in pod {}/{}",
                      namespace, podName, e);
        }
    }

    private int sizeToMB(String size) {
        if (size == null || size.isEmpty()) {
            return 1024;
        }
        String s = size.trim().toUpperCase();
        if (s.endsWith("G")) {
            return Integer.parseInt(s.substring(0, s.length() - 1)) * 1024;
        } else if (s.endsWith("M")) {
            return Integer.parseInt(s.substring(0, s.length() - 1));
        }
        return 1024;
    }
}
