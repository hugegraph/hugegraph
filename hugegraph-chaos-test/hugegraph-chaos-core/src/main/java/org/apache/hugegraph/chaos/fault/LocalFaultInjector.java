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

import org.apache.hugegraph.chaos.model.FaultType;
import org.apache.hugegraph.chaos.model.Step;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LocalFaultInjector implements FaultInjector {

    private static final Logger LOG = LogManager.getLogger(LocalFaultInjector.class);
    private final Map<String, Process> activeProcesses = new ConcurrentHashMap<>();
    private Boolean sudoAvailable = null;

    @Override
    public void inject(Step.StepAction action) throws Exception {
        FaultType type = FaultType.fromValue(action.getType());
        if (type == null) {
            throw new UnsupportedOperationException(
                "Unknown fault type: " + action.getType());
        }
        switch (type) {
            case PROCESS_KILL:
                injectProcessKill(action);
                break;
            case PROCESS_PAUSE:
                injectProcessPause(action);
                break;
            case HOST_SHUTDOWN:
                injectHostShutdown(action);
                break;
            case NETWORK_DELAY:
                injectNetworkDelay(action);
                break;
            case NETWORK_LOSS:
                injectNetworkLoss(action);
                break;
            case NETWORK_PARTITION:
                injectNetworkPartition(action);
                break;
            case NETWORK_BANDWIDTH:
                injectNetworkBandwidth(action);
                break;
            case NETWORK_CORRUPT:
                injectNetworkCorrupt(action);
                break;
            case NETWORK_DUP:
                injectNetworkDup(action);
                break;
            case CPU_STRESS:
                injectCpuStress(action);
                break;
            case MEMORY_STRESS:
                injectMemoryStress(action);
                break;
            case DISK_STRESS:
                injectDiskStress(action);
                break;
            case IO_STRESS:
                injectIoStress(action);
                break;
            case DISK_FAULT:
                injectDiskFault(action);
                break;
            case DISK_FILL:
                injectDiskFill(action);
                break;
            case JVM_LATENCY:
            case JVM_EXCEPTION:
            case JVM_RETURN:
            case JVM_OOM:
            case JVM_GC:
            case JVM_THREAD_DEADLOCK:
                injectJvmFault(action, type);
                break;
            case DNS_FAULT:
                injectDnsFault(action);
                break;
            case TIME_SKEW:
                injectTimeSkew(action);
                break;
            case KERNEL_FAULT:
                injectKernelFault(action);
                break;
            default:
                throw new SkippableFaultException(
                    "Fault type '" + type.getValue() +
                    "' is not supported in local environment");
        }
    }

    @Override
    public void recover(Step.StepAction action) throws Exception {
        FaultType type = FaultType.fromValue(action.getType());
        if (type == null) {
            LOG.warn("Unknown fault type for recovery: {}", action.getType());
            return;
        }
        switch (type) {
            case PROCESS_KILL:
                LOG.info("Process recovery for: {} (auto-restart expected)",
                         action.getTarget());
                break;
            case PROCESS_PAUSE:
                recoverProcessPause(action);
                break;
            case HOST_SHUTDOWN:
                LOG.info("Host recovery: {} (manual restart required)",
                         action.getTarget());
                break;
            case NETWORK_DELAY:
            case NETWORK_LOSS:
            case NETWORK_PARTITION:
            case NETWORK_BANDWIDTH:
            case NETWORK_CORRUPT:
            case NETWORK_DUP:
                recoverNetwork();
                break;
            case CPU_STRESS:
            case MEMORY_STRESS:
            case DISK_STRESS:
            case IO_STRESS:
                recoverStress(action);
                break;
            case DISK_FAULT:
                recoverDiskFault(action);
                break;
            case DISK_FILL:
                recoverDiskFill(action);
                break;
            case JVM_LATENCY:
            case JVM_EXCEPTION:
            case JVM_RETURN:
            case JVM_OOM:
            case JVM_GC:
            case JVM_THREAD_DEADLOCK:
                recoverJvmFault(action);
                break;
            case DNS_FAULT:
                recoverDnsFault(action);
                break;
            case TIME_SKEW:
                recoverTimeSkew();
                break;
            case KERNEL_FAULT:
                recoverKernelFault(action);
                break;
            default:
                LOG.warn("No recovery for fault type: {}", type);
        }
    }

    @Override
    public void healAll() {
        LOG.info("Healing all active faults");
        for (Map.Entry<String, Process> entry :
             new ArrayList<>(activeProcesses.entrySet())) {
            try {
                entry.getValue().destroyForcibly();
                activeProcesses.remove(entry.getKey());
            } catch (Exception e) {
                LOG.error("Failed to heal fault: {}", entry.getKey(), e);
            }
        }
        try {
            executeCommand(tcPrefix() +
                           "tc qdisc del dev lo root 2>/dev/null || true");
        } catch (Exception e) {
            LOG.debug("No tc qdisc to clean on lo");
        }
    }

    private boolean isSudoAvailable() {
        if (sudoAvailable != null) {
            return sudoAvailable;
        }
        try {
            Process process = Runtime.getRuntime().exec(
                new String[]{"sh", "-c", "sudo -n true 2>/dev/null"});
            int exitCode = process.waitFor();
            sudoAvailable = exitCode == 0;
        } catch (Exception e) {
            sudoAvailable = false;
        }
        LOG.info("Sudo available: {}", sudoAvailable);
        return sudoAvailable;
    }

    private String tcPrefix() {
        return isSudoAvailable() ? "sudo " : "";
    }

    private void ensureTcAvailable() throws SkippableFaultException {
        try {
            executeCommand("which tc 2>/dev/null");
        } catch (Exception e) {
            throw new SkippableFaultException(
                "Network fault injection requires 'tc' (iproute2), " +
                "not available on this platform");
        }
    }

    private void ensureTcQdiscClean() {
        try {
            executeCommand(tcPrefix() +
                           "tc qdisc del dev lo root 2>/dev/null || true");
        } catch (Exception e) {
            LOG.debug("No existing tc qdisc to clean on lo");
        }
    }

    private void ensureStressNgAvailable() throws SkippableFaultException {
        try {
            executeCommand("which stress-ng 2>/dev/null");
        } catch (Exception e) {
            throw new SkippableFaultException(
                "Stress fault injection requires 'stress-ng', " +
                "not available on this platform");
        }
    }

    private long durationSeconds(Step.StepAction action) {
        return action.getDuration() != null
               ? parseDuration(action.getDuration()).getSeconds()
               : 60;
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

    private void injectProcessKill(Step.StepAction action) {
        String target = action.getTarget();
        LOG.info("Killing process: {}", target);
        try {
            ProcessHandle.allProcesses()
                         .filter(p -> p.info().commandLine()
                                       .orElse("").contains(target))
                         .forEach(p -> {
                             LOG.info("Destroying process: {}", p.pid());
                             p.destroyForcibly();
                         });
        } catch (Exception e) {
            LOG.error("Failed to kill process: {}", target, e);
        }
    }

    private void injectProcessPause(Step.StepAction action)
                                     throws SkippableFaultException {
        String target = action.getTarget();
        LOG.info("Pausing process: {}", target);
        try {
            ProcessHandle.allProcesses()
                         .filter(p -> p.info().commandLine()
                                       .orElse("").contains(target))
                         .forEach(p -> {
                             long pid = p.pid();
                             try {
                                 executeCommand("kill -STOP " + pid);
                             } catch (Exception e) {
                                 LOG.error("Failed to pause process: {}",
                                           pid, e);
                             }
                         });
        } catch (Exception e) {
            throw new SkippableFaultException(
                "Failed to pause process: " + target);
        }
    }

    private void recoverProcessPause(Step.StepAction action) {
        String target = action.getTarget();
        LOG.info("Resuming process: {}", target);
        try {
            ProcessHandle.allProcesses()
                         .filter(p -> p.info().commandLine()
                                       .orElse("").contains(target))
                         .forEach(p -> {
                             try {
                                 executeCommand("kill -CONT " + p.pid());
                             } catch (Exception e) {
                                 LOG.error("Failed to resume process: {}",
                                           p.pid(), e);
                             }
                         });
        } catch (Exception e) {
            LOG.error("Failed to resume process: {}", target, e);
        }
    }

    private void injectHostShutdown(Step.StepAction action)
                                     throws SkippableFaultException {
        throw new SkippableFaultException(
            "Host shutdown fault injection is not supported in local " +
            "environment (would shut down the test machine)");
    }

    private void injectNetworkDelay(Step.StepAction action)
                                     throws Exception {
        Map<String, Object> params = action.getParams();
        int latency = paramInt(params, "latency", 100);
        int jitter = paramInt(params, "jitter", 0);
        LOG.info("Injecting network delay: {}ms +/- {}ms", latency, jitter);
        ensureTcAvailable();
        ensureTcQdiscClean();
        String cmd = String.format(
            "%stc qdisc add dev lo root netem delay %dms %dms",
            tcPrefix(), latency, jitter);
        executeCommand(cmd);
    }

    private void injectNetworkLoss(Step.StepAction action)
                                    throws Exception {
        Map<String, Object> params = action.getParams();
        double loss = params.get("loss") instanceof Number
                      ? ((Number) params.get("loss")).doubleValue() : 10.0;
        LOG.info("Injecting network loss: {}%", loss);
        ensureTcAvailable();
        ensureTcQdiscClean();
        String cmd = String.format(
            "%stc qdisc add dev lo root netem loss %.1f%%",
            tcPrefix(), loss);
        executeCommand(cmd);
    }

    private void injectNetworkPartition(Step.StepAction action)
                                         throws Exception {
        Map<String, Object> params = action.getParams();
        String targetIp = paramString(params, "target_ip", "");
        LOG.info("Injecting network partition to: {}", targetIp);
        ensureTcAvailable();
        ensureTcQdiscClean();
        if (targetIp.isEmpty()) {
            String cmd = String.format(
                "%stc qdisc add dev lo root netem loss 100%%",
                tcPrefix());
            executeCommand(cmd);
        } else {
            String cmd = String.format(
                "%siptables -A OUTPUT -d %s -j DROP",
                isSudoAvailable() ? "sudo " : "", targetIp);
            executeCommand(cmd);
        }
    }

    private void injectNetworkBandwidth(Step.StepAction action)
                                         throws Exception {
        Map<String, Object> params = action.getParams();
        String rate = paramString(params, "rate", "1mbit");
        LOG.info("Injecting network bandwidth limit: {}", rate);
        ensureTcAvailable();
        ensureTcQdiscClean();
        String cmd = String.format(
            "%stc qdisc add dev lo root tbf rate %s burst 32kbit latency 400ms",
            tcPrefix(), rate);
        executeCommand(cmd);
    }

    private void injectNetworkCorrupt(Step.StepAction action)
                                       throws Exception {
        Map<String, Object> params = action.getParams();
        double corrupt = params.get("corrupt") instanceof Number
                         ? ((Number) params.get("corrupt")).doubleValue()
                         : 10.0;
        LOG.info("Injecting network corrupt: {}%", corrupt);
        ensureTcAvailable();
        ensureTcQdiscClean();
        String cmd = String.format(
            "%stc qdisc add dev lo root netem corrupt %.1f%%",
            tcPrefix(), corrupt);
        executeCommand(cmd);
    }

    private void injectNetworkDup(Step.StepAction action)
                                   throws Exception {
        Map<String, Object> params = action.getParams();
        double dup = params.get("dup") instanceof Number
                     ? ((Number) params.get("dup")).doubleValue() : 10.0;
        LOG.info("Injecting network duplication: {}%", dup);
        ensureTcAvailable();
        ensureTcQdiscClean();
        String cmd = String.format(
            "%stc qdisc add dev lo root netem duplicate %.1f%%",
            tcPrefix(), dup);
        executeCommand(cmd);
    }

    private void recoverNetwork() throws Exception {
        LOG.info("Recovering network rules");
        executeCommand(tcPrefix() + "tc qdisc del dev lo root 2>/dev/null || true");
        executeCommand(
            (isSudoAvailable() ? "sudo " : "") +
            "iptables -F OUTPUT 2>/dev/null || true");
    }

    private void injectCpuStress(Step.StepAction action) throws Exception {
        Map<String, Object> params = action.getParams();
        int cores = paramInt(params, "cores", 1);
        long durationSec = durationSeconds(action);
        LOG.info("Injecting CPU stress: {} cores for {}s", cores, durationSec);
        ensureStressNgAvailable();
        String cmd = String.format("stress-ng --cpu %d --timeout %ds",
                                   cores, durationSec);
        Process process = executeCommandAsync(cmd);
        activeProcesses.put(action.getTarget() + "-cpu", process);
    }

    private void injectMemoryStress(Step.StepAction action)
                                     throws Exception {
        Map<String, Object> params = action.getParams();
        long durationSec = durationSeconds(action);
        String vmBytes;
        Object sizeObj = params.get("size");
        Object percentObj = params.get("percent");
        if (sizeObj != null) {
            vmBytes = sizeObj.toString();
        } else if (percentObj != null) {
            vmBytes = ((Number) percentObj).intValue() + "%";
        } else {
            vmBytes = "50%";
        }
        LOG.info("Injecting memory stress: {} for {}s", vmBytes, durationSec);
        ensureStressNgAvailable();
        String cmd = String.format("stress-ng --vm 1 --vm-bytes %s --timeout %ds",
                                   vmBytes, durationSec);
        Process process = executeCommandAsync(cmd);
        activeProcesses.put(action.getTarget() + "-memory", process);
    }

    private void injectDiskStress(Step.StepAction action) throws Exception {
        Map<String, Object> params = action.getParams();
        long durationSec = durationSeconds(action);
        LOG.info("Injecting disk stress for {}s", durationSec);
        ensureStressNgAvailable();
        String cmd = String.format("stress-ng --hdd 1 --timeout %ds",
                                   durationSec);
        Process process = executeCommandAsync(cmd);
        activeProcesses.put(action.getTarget() + "-disk", process);
    }

    private void injectIoStress(Step.StepAction action) throws Exception {
        Map<String, Object> params = action.getParams();
        long durationSec = durationSeconds(action);
        LOG.info("Injecting I/O stress for {}s", durationSec);
        ensureStressNgAvailable();
        String cmd = String.format("stress-ng --io 1 --timeout %ds",
                                   durationSec);
        Process process = executeCommandAsync(cmd);
        activeProcesses.put(action.getTarget() + "-io", process);
    }

    private void recoverStress(Step.StepAction action) {
        String target = action.getTarget();
        destroyProcess(target + "-cpu");
        destroyProcess(target + "-memory");
        destroyProcess(target + "-disk");
        destroyProcess(target + "-io");
    }

    private void injectDiskFault(Step.StepAction action)
                                  throws SkippableFaultException {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fault");
        LOG.info("Injecting disk fault at: {}", path);
        try {
            executeCommand("mkdir -p " + path);
            executeCommand("chmod 000 " + path);
        } catch (Exception e) {
            throw new SkippableFaultException(
                "Failed to inject disk fault: " + e.getMessage());
        }
    }

    private void recoverDiskFault(Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fault");
        LOG.info("Recovering disk fault at: {}", path);
        try {
            executeCommand("chmod 755 " + path + " 2>/dev/null || true");
            executeCommand("rm -rf " + path + " 2>/dev/null || true");
        } catch (Exception e) {
            LOG.warn("Failed to recover disk fault", e);
        }
    }

    private void injectDiskFill(Step.StepAction action)
                                 throws SkippableFaultException {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fill");
        String size = paramString(params, "size", "1G");
        LOG.info("Injecting disk fill at: {} size: {}", path, size);
        try {
            executeCommand("mkdir -p " + path);
            executeCommand("dd if=/dev/zero of=" + path +
                           "/fill bs=1M count=" +
                           parseSizeToMB(size) + " 2>/dev/null");
        } catch (Exception e) {
            throw new SkippableFaultException(
                "Failed to inject disk fill: " + e.getMessage());
        }
    }

    private void recoverDiskFill(Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String path = paramString(params, "path", "/tmp/chaos-disk-fill");
        LOG.info("Recovering disk fill at: {}", path);
        try {
            executeCommand("rm -rf " + path + " 2>/dev/null || true");
        } catch (Exception e) {
            LOG.warn("Failed to recover disk fill", e);
        }
    }

    private void injectJvmFault(Step.StepAction action, FaultType type)
                                 throws SkippableFaultException {
        LOG.info("Injecting JVM fault: {} on target: {}",
                 type.getValue(), action.getTarget());
        throw new SkippableFaultException(
            "JVM fault injection ('" + type.getValue() +
            "') requires a Java agent attached to the target JVM. " +
            "Use byteman or chaos-mesh JVM chaos agent in production.");
    }

    private void recoverJvmFault(Step.StepAction action) {
        LOG.info("JVM fault recovery for: {}", action.getTarget());
    }

    private void injectDnsFault(Step.StepAction action)
                                 throws SkippableFaultException {
        Map<String, Object> params = action.getParams();
        String domain = paramString(params, "domain", "");
        String resolveTo = paramString(params, "resolve_to", "0.0.0.0");
        LOG.info("Injecting DNS fault: {} -> {}", domain, resolveTo);
        try {
            String entry = resolveTo + " " + domain;
            executeCommand("echo '" + entry +
                           "' | sudo tee -a /etc/hosts 2>/dev/null || " +
                           "echo '" + entry + "' >> /tmp/chaos-hosts");
        } catch (Exception e) {
            throw new SkippableFaultException(
                "DNS fault injection requires write access to /etc/hosts: " +
                e.getMessage());
        }
    }

    private void recoverDnsFault(Step.StepAction action) {
        Map<String, Object> params = action.getParams();
        String domain = paramString(params, "domain", "");
        LOG.info("Recovering DNS fault for: {}", domain);
        try {
            executeCommand(
                "sudo sed -i '/" + domain +
                "/d' /etc/hosts 2>/dev/null || true");
        } catch (Exception e) {
            LOG.warn("Failed to recover DNS fault", e);
        }
    }

    private void injectTimeSkew(Step.StepAction action)
                                 throws SkippableFaultException {
        Map<String, Object> params = action.getParams();
        String offset = paramString(params, "offset", "+60s");
        LOG.info("Injecting time skew: {}", offset);
        try {
            executeCommand("sudo date -s \"$(date -d '" + offset +
                           "')\" 2>/dev/null || true");
        } catch (Exception e) {
            throw new SkippableFaultException(
                "Time skew injection requires sudo and Linux date command: " +
                e.getMessage());
        }
    }

    private void recoverTimeSkew() {
        LOG.info("Recovering time skew (restarting NTP)");
        try {
            executeCommand(
                "sudo ntpdate -u pool.ntp.org 2>/dev/null || " +
                "sudo systemctl restart chronyd 2>/dev/null || true");
        } catch (Exception e) {
            LOG.warn("Failed to recover time skew", e);
        }
    }

    private void injectKernelFault(Step.StepAction action)
                                    throws SkippableFaultException {
        Map<String, Object> params = action.getParams();
        String fault = paramString(params, "fault", "panic");
        LOG.info("Injecting kernel fault: {}", fault);
        throw new SkippableFaultException(
            "Kernel fault injection ('" + fault +
            "') requires fault-injector kernel module and root access. " +
            "Not supported in standard test environments.");
    }

    private void recoverKernelFault(Step.StepAction action) {
        LOG.info("Kernel fault recovery for: {}", action.getTarget());
    }

    private void destroyProcess(String key) {
        Process process = activeProcesses.remove(key);
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private void executeCommand(String command) throws Exception {
        LOG.debug("Executing: {}", command);
        Process process = Runtime.getRuntime().exec(
            new String[]{"sh", "-c", command});
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String error = readStream(process.getErrorStream());
            throw new RuntimeException(
                "Command failed with exit code " + exitCode + ": " + error);
        }
    }

    private Process executeCommandAsync(String command) throws Exception {
        LOG.debug("Executing async: {}", command);
        return Runtime.getRuntime().exec(
            new String[]{"sh", "-c", command});
    }

    private String readStream(java.io.InputStream stream) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader =
             new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private int parseSizeToMB(String size) {
        if (size == null || size.isEmpty()) {
            return 1024;
        }
        String s = size.trim().toUpperCase();
        if (s.endsWith("G")) {
            return Integer.parseInt(s.substring(0, s.length() - 1)) * 1024;
        } else if (s.endsWith("M")) {
            return Integer.parseInt(s.substring(0, s.length() - 1));
        } else if (s.endsWith("K")) {
            return Integer.parseInt(s.substring(0, s.length() - 1)) / 1024;
        }
        return Integer.parseInt(s);
    }

    private Duration parseDuration(String value) {
        if (value == null || value.isEmpty()) {
            return Duration.ZERO;
        }
        String v = value.trim().toLowerCase();
        if (v.endsWith("ms")) {
            return Duration.ofMillis(
                Long.parseLong(v.substring(0, v.length() - 2)));
        } else if (v.endsWith("s")) {
            return Duration.ofSeconds(
                Long.parseLong(v.substring(0, v.length() - 1)));
        } else if (v.endsWith("m")) {
            return Duration.ofMinutes(
                Long.parseLong(v.substring(0, v.length() - 1)));
        } else if (v.endsWith("h")) {
            return Duration.ofHours(
                Long.parseLong(v.substring(0, v.length() - 1)));
        } else {
            return Duration.ofSeconds(Long.parseLong(v));
        }
    }
}
