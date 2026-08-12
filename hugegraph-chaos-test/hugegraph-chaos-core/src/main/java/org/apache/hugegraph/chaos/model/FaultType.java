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

package org.apache.hugegraph.chaos.model;

public enum FaultType {

    PROCESS_KILL("process_kill"),
    PROCESS_PAUSE("process_pause"),
    HOST_SHUTDOWN("host_shutdown"),

    NETWORK_DELAY("network_delay"),
    NETWORK_LOSS("network_loss"),
    NETWORK_PARTITION("network_partition"),
    NETWORK_BANDWIDTH("network_bandwidth"),
    NETWORK_CORRUPT("network_corrupt"),
    NETWORK_DUP("network_dup"),

    CPU_STRESS("cpu_stress"),
    MEMORY_STRESS("memory_stress"),
    DISK_STRESS("disk_stress"),
    IO_STRESS("io_stress"),

    DISK_FAULT("disk_fault"),
    DISK_FILL("disk_fill"),

    JVM_LATENCY("jvm_latency"),
    JVM_EXCEPTION("jvm_exception"),
    JVM_RETURN("jvm_return"),
    JVM_OOM("jvm_oom"),
    JVM_GC("jvm_gc"),
    JVM_THREAD_DEADLOCK("jvm_thread_deadlock"),

    DNS_FAULT("dns_fault"),

    TIME_SKEW("time_skew"),

    KERNEL_FAULT("kernel_fault"),

    POD_KILL("pod_kill"),
    POD_FAILURE("pod_failure"),
    CONTAINER_KILL("container_kill"),

    HTTP_FAULT("http_fault"),

    AWS_EC2_STOP("aws_ec2_stop"),
    AWS_EC2_REBOOT("aws_ec2_reboot"),
    AWS_EBS_LOSS("aws_ebs_loss"),
    AWS_RDS_REBOOT("aws_rds_reboot"),

    GCE_INSTANCE_STOP("gce_instance_stop"),
    GCE_INSTANCE_RESET("gce_instance_reset"),
    GCE_DISK_LOSS("gce_disk_loss");

    private final String value;

    FaultType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static FaultType fromValue(String value) {
        for (FaultType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
