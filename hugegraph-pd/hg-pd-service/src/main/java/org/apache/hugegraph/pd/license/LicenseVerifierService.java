// TODO: uncomment later
///*
// * Licensed to the Apache Software Foundation (ASF) under one or more
// * contributor license agreements. See the NOTICE file distributed with this
// * work for additional information regarding copyright ownership. The ASF
// * licenses this file to You under the Apache License, Version 2.0 (the
// * "License"); you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// * License for the specific language governing permissions and limitations
// * under the License.
// */
//
//package org.apache.hugegraph.pd.license;
//
//import java.io.File;
//import java.io.IOException;
//import java.net.InetAddress;
//import java.net.UnknownHostException;
//import java.nio.charset.Charset;
//import java.text.SimpleDateFormat;
//import java.time.Duration;
//import java.time.Instant;
//import java.util.Date;
//import java.util.HashMap;
//import java.util.List;
//import java.util.concurrent.CountDownLatch;
//import java.util.prefs.Preferences;
//
//import org.apache.commons.lang3.StringUtils;
//import org.apache.hugegraph.license.ExtraParam;
//import org.apache.hugegraph.license.LicenseVerifyParam;
//import org.apache.hugegraph.license.MachineInfo;
//import org.apache.hugegraph.pd.KvService;
//import org.apache.hugegraph.pd.common.PDRuntimeException;
//import org.apache.hugegraph.pd.config.PDConfig;
//import org.apache.hugegraph.pd.grpc.Pdpb;
//import org.apache.hugegraph.pd.grpc.kv.KvServiceGrpc;
//import org.apache.hugegraph.pd.grpc.kv.TTLRequest;
//import org.apache.hugegraph.pd.grpc.kv.TTLResponse;
//import org.apache.hugegraph.pd.raft.RaftEngine;
//import org.springframework.stereotype.Service;
//import org.springframework.util.Base64Utils;
//
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.google.gson.Gson;
//import com.google.gson.internal.LinkedTreeMap;
//
//import de.schlichtherle.license.CipherParam;
//import de.schlichtherle.license.DefaultCipherParam;
//import de.schlichtherle.license.DefaultKeyStoreParam;
//import de.schlichtherle.license.DefaultLicenseParam;
//import de.schlichtherle.license.KeyStoreParam;
//import de.schlichtherle.license.LicenseContent;
//import de.schlichtherle.license.LicenseParam;
//import io.grpc.CallOptions;
//import io.grpc.ManagedChannel;
//import io.grpc.ManagedChannelBuilder;
//import io.grpc.MethodDescriptor;
//import io.grpc.stub.AbstractBlockingStub;
//import io.grpc.stub.StreamObserver;
//import lombok.extern.slf4j.Slf4j;
//
//@Service
//@Slf4j
//public class LicenseVerifierService {
//
//    private static final Duration CHECK_INTERVAL = Duration.ofMinutes(10);
//    private static final String contentKey = "contentKey";
//    private static final Gson mapper = new Gson();
//    private static LicenseContent content;
//    private static KvService kvService;
//    private static volatile boolean installed = false;
//    private final MachineInfo machineInfo;
//    private final PDConfig pdConfig;
//    private final Instant lastCheckTime = Instant.now();
//    SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
//    // private final LicenseVerifyParam verifyParam;
//    private LicenseVerifyManager manager;
//    private ManagedChannel channel;
//
//    // public static LicenseVerifierService instance() {
//    //    if (INSTANCE == null) {
//    //        synchronized (LicenseVerifierService.class) {
//    //            if (INSTANCE == null) {
//    //                INSTANCE = new LicenseVerifierService();
//    //            }
//    //        }
//    //    }
//    //    return INSTANCE;
//    // }
//
//    // public void verifyIfNeeded() {
//    //    Instant now = Instant.now();
//    //    Duration interval = Duration.between(this.lastCheckTime, now);
//    //    if (!interval.minus(CHECK_INTERVAL).isNegative()) {
//    //        this.verify();
//    //        this.lastCheckTime = now;
//    //    }
//    // }
//
//    public LicenseVerifierService(PDConfig pdConfig) {
//        this.pdConfig = pdConfig;
//        machineInfo = new MachineInfo();
//        kvService = new KvService(pdConfig);
//        // verifyParam = initLicense(pdConfig);
//    }
//
//    private static LicenseVerifyParam buildVerifyParam(String path) {
//        // NOTE: can't use JsonUtil due to it bind tinkerpop jackson
//        try {
//            ObjectMapper mapper = new ObjectMapper();
//            File licenseParamFile = new File(path);
//            if (!licenseParamFile.exists()) {
//                log.warn("failed to get file:{}", path);
//                return null;
//            }
//            return mapper.readValue(licenseParamFile, LicenseVerifyParam.class);
//        } catch (IOException e) {
//            throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE,
//                                         String.format("Failed to read json stream to %s",
//                                                       LicenseVerifyParam.class));
//        }
//    }
//
//    public LicenseVerifyParam init() {
//        LicenseVerifyParam verifyParam = null;
//        if (!installed) {
//            synchronized (LicenseVerifierService.class) {
//                if (!installed) {
//                    verifyParam = buildVerifyParam(pdConfig.getVerifyPath());
//                    log.info("get license param: {}", pdConfig.getVerifyPath());
//                    if (verifyParam != null) {
//                        LicenseParam licenseParam = this.initLicenseParam(verifyParam);
//                        this.manager = new LicenseVerifyManager(licenseParam);
//                        // this.install("d01e1814cd9edb01a05671bebf3919cc");
//                        try {
//                            // this.verifyPublicCert(md5);
//                            File licenseFile = new File(pdConfig.getLicensePath());
//                            if (!licenseFile.exists()) {
//                                log.warn("invalid parameter:license-path");
//                                return null;
//                            } else {
//                                log.info("get license file....{}", licenseFile.getAbsolutePath());
//                            }
//                            this.manager.uninstall();
//                            content = this.manager.install(licenseFile);
//                            ExtraParam param = LicenseVerifyManager.getExtraParams(content);
//                            content.setExtra(param);
//                            this.checkIpAndMac(param);
//                            // 获取有效期，并设置过期时间，通知 leader，将 content 保存到...
//                            Date notAfter = content.getNotAfter();
//                            long ttl = notAfter.getTime() - System.currentTimeMillis();
//                            final TTLResponse[] info = {null};
//                            if (!isLeader()) {
//                                while (RaftEngine.getInstance().getLeader() == null) {
//                                    this.wait(200);
//                                }
//                                if (RaftEngine.getInstance().getLeader() != null) {
//                                    CountDownLatch latch = new CountDownLatch(1);
//                                    TTLRequest request =
//                                            TTLRequest.newBuilder().setKey(contentKey).setValue(
//                                                              mapper.toJson(content,
//                                                                            LicenseContent.class))
//                                                      .setTtl(ttl).build();
//                                    StreamObserver<TTLResponse> observer =
//                                            new StreamObserver<TTLResponse>() {
//                                                @Override
//                                                public void onNext(TTLResponse value) {
//                                                    info[0] = value;
//                                                    latch.countDown();
//                                                }
//
//                                                @Override
//                                                public void onError(Throwable t) {
//                                                    latch.countDown();
//                                                }
//
//                                                @Override
//                                                public void onCompleted() {
//                                                    latch.countDown();
//                                                }
//                                            };
//                                    redirectToLeader(KvServiceGrpc.getPutTTLMethod(), request,
//                                                     observer);
//                                    latch.await();
//                                    Pdpb.Error error = info[0].getHeader().getError();
//                                    if (!error.getType().equals(Pdpb.ErrorType.OK)) {
//                                        throw new Exception(error.getMessage());
//                                    }
//                                } else {
//                                    log.warn("wait for leader to put the license content......");
//                                }
//
//                            } else {
//                                kvService.put(contentKey,
//                                              mapper.toJson(content, LicenseContent.class), ttl);
//                            }
//                            installed = true;
//                            log.info("The license is successfully installed, valid for {} - {}",
//                                     content.getNotBefore(), notAfter);
//                        } catch (Exception e) {
//                            log.error("Failed to install license", e);
//                            throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_ERROR_VALUE,
//                                                         "Failed to install license, ", e);
//                        }
//                    }
//                }
//            }
//        }
//        return verifyParam;
//    }
//
//    public synchronized void install(String md5) {
//
//    }
//
//    public HashMap getContext() throws Exception {
//        try {
//            String value = kvService.get(contentKey);
//            if (StringUtils.isEmpty(value)) {
//                throw new Exception("can not find license content from storage");
//            }
//            LicenseContent content = mapper.fromJson(value, LicenseContent.class);
//            Date notAfter = content.getNotAfter();
//            Date notBefore = content.getNotBefore();
//            Date issued = content.getIssued();
//            // long currentTimeMillis = System.currentTimeMillis();
//            // long diff = notAfter - currentTimeMillis;
//            // boolean expired = diff <= 0;
//            HashMap result = mapper.fromJson(value, HashMap.class);
//            result.put("current", formatter.format(new Date()));
//            result.put("notAfter", formatter.format(notAfter));
//            result.put("issued", formatter.format(issued));
//            result.put("notBefore", formatter.format(notBefore));
//            return result;
//        } catch (Exception e) {
//            throw new Exception("can not find license content from storage:" + e.getMessage());
//        }
//    }
//
//    public LicenseContent verify(int cores, int nodeCount) {
//        try {
//            String value = kvService.get(contentKey);
//            if (StringUtils.isEmpty(value)) {
//                throw new Exception("can not find license content from storage");
//            }
//            LicenseContent content = mapper.fromJson(value, LicenseContent.class);
//            LinkedTreeMap param = (LinkedTreeMap) content.getExtra();
//            int licCpus = ((Double) param.get("cpus")).intValue();
//            int licNodes = ((Double) param.get("nodes")).intValue();
//            if (param != null) {
//                if (licCpus != -1) {
//                    // licCpus 为 -1 时，表示不限制 cpu 核数
//                    if (cores <= 0 || cores > licCpus) {
//                        String msg =
//                                String.format("无效的 cpu 核数：%s，授权数：%s", cores, licCpus);
//                        throw new PDRuntimeException(
//                                Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE, msg);
//                    }
//                }
//
//                if (licNodes != -1) {
//                    // licNodes 为 -1 时，表示不限制服务节点数目
//                    if (nodeCount > licNodes) {
//                        String msg =
//                                String.format("无效的节点个数：%s，授权数：%s", nodeCount, licNodes);
//                        throw new PDRuntimeException(
//                                Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE, msg);
//                    }
//                }
//            }
//            return content;
//        } catch (Exception e) {
//            throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE,
//                                         "授权信息校验异常，" + e.getMessage());
//        }
//    }
//
//    public boolean isLeader() {
//        return RaftEngine.getInstance().isLeader();
//    }
//
//    // private void verifyPublicCert(String expectMD5) {
//    //    String path = this.verifyParam.publicKeyPath();
//    //    try (InputStream is = LicenseVerifierService.class.getResourceAsStream(path)) {
//    //        String actualMD5 = DigestUtils.md5Hex(is);
//    //        if (!actualMD5.equals(expectMD5)) {
//    //            throw new PDRuntimeException(PDRuntimeException.LICENSE_ERROR, "Invalid public
//    //            cert");
//    //        }
//    //    } catch (IOException e) {
//    //        log.error("Failed to read public cert", e);
//    //        throw new PDRuntimeException(PDRuntimeException.LICENSE_ERROR, "Failed to read
//    //        public cert", e);
//    //    }
//    // }
//
//    private <ReqT, RespT, StubT extends AbstractBlockingStub<StubT>> void redirectToLeader(
//            MethodDescriptor<ReqT, RespT> method, ReqT req,
//            io.grpc.stub.StreamObserver<RespT> observer) {
//        try {
//            if (channel == null) {
//                synchronized (this) {
//                    if (channel == null) {
//                        channel = ManagedChannelBuilder
//                                .forTarget(RaftEngine.getInstance().getLeaderGrpcAddress())
//                                .usePlaintext()
//                                .build();
//                    }
//                }
//                log.info("Grpc get leader address {}",
//                         RaftEngine.getInstance().getLeaderGrpcAddress());
//            }
//
//            io.grpc.stub.ClientCalls.asyncUnaryCall(channel.newCall(method, CallOptions.DEFAULT),
//                                                    req,
//                                                    observer);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//    }
//
//    private LicenseParam initLicenseParam(LicenseVerifyParam param) {
//        Preferences preferences = Preferences.userNodeForPackage(LicenseVerifierService.class);
//        CipherParam cipherParam = new DefaultCipherParam(param.storePassword());
//        KeyStoreParam keyStoreParam = new DefaultKeyStoreParam(LicenseVerifierService.class,
//                                                               param.publicKeyPath(),
//                                                               param.publicAlias(),
//                                                               param.storePassword(), null);
//        return new DefaultLicenseParam(param.subject(), preferences, keyStoreParam, cipherParam);
//    }
//
//    public String getIpAndMac() {
//        List<String> actualIps = this.machineInfo.getIpAddress();
//        String host = pdConfig.getHost();
//        String licenseHost = host;
//        if (!actualIps.contains(host)) {
//            licenseHost = actualIps.get(0);
//        }
//        try {
//            String mac = this.machineInfo.getMacByInetAddress(InetAddress.getByName(licenseHost));
//            HashMap<String, String> ipAndMac = new HashMap<>();
//            ipAndMac.put("ip", licenseHost);
//            ipAndMac.put("mac", mac);
//            String json = new Gson().toJson(ipAndMac);
//            String encode = Base64Utils.encodeToString(json.getBytes(Charset.defaultCharset()));
//            return encode;
//        } catch (Exception e) {
//            throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_ERROR_VALUE,
//                                         String.format("Failed to get ip and mac for %s",
//                                                       e.getMessage()));
//        }
//    }
//
//    private void checkIpAndMac(ExtraParam param) {
//        String expectIp = param.ip();
//        boolean matched = false;
//        List<String> actualIps = null;
//        if (StringUtils.isEmpty(expectIp)) {
//            matched = true;
//        } else {
//            actualIps = this.machineInfo.getIpAddress();
//            for (String actualIp : actualIps) {
//                if (actualIp.equalsIgnoreCase(expectIp)) {
//                    matched = true;
//                    break;
//                }
//            }
//        }
//        if (!matched) {
//            throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE, String.format(
//                    "The server's ip '%s' doesn't match the authorized '%s'", actualIps, 
//                    expectIp));
//        }
//        String expectMac = param.mac();
//        if (StringUtils.isEmpty(expectMac)) {
//            return;
//        }
//        // The mac must be not empty here
//        if (!StringUtils.isEmpty(expectIp)) {
//            String actualMac;
//            try {
//                actualMac = this.machineInfo.getMacByInetAddress(InetAddress.getByName(expectIp));
//            } catch (UnknownHostException e) {
//                throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE,
//                                             String.format("Failed to get mac address for ip 
//                                             '%s'",
//                                                           expectIp));
//            }
//            String expectFormatMac = expectMac.replaceAll(":", "-");
//            String actualFormatMac = actualMac.replaceAll(":", "-");
//            if (!actualFormatMac.equalsIgnoreCase(expectFormatMac)) {
//                throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE,
//                                             String.format(
//                                                     "The server's mac '%s' doesn't match the " +
//                                                     "authorized '%s'",
//                                                     actualMac, expectMac));
//            }
//        } else {
//            String expectFormatMac = expectMac.replaceAll(":", "-");
//            List<String> actualMacs = this.machineInfo.getMacAddress();
//            matched = false;
//            for (String actualMac : actualMacs) {
//                String actualFormatMac = actualMac.replaceAll(":", "-");
//                if (actualFormatMac.equalsIgnoreCase(expectFormatMac)) {
//                    matched = true;
//                    break;
//                }
//            }
//            if (!matched) {
//                throw new PDRuntimeException(Pdpb.ErrorType.LICENSE_VERIFY_ERROR_VALUE,
//                                             String.format(
//                                                     "The server's macs %s don't match the " +
//                                                     "authorized '%s'",
//                                                     actualMacs, expectMac));
//            }
//        }
//    }
//}
