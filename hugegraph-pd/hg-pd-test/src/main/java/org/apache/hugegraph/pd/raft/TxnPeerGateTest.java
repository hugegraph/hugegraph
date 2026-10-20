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

package org.apache.hugegraph.pd.raft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hugegraph.pd.common.PDException;
import org.apache.hugegraph.pd.grpc.Pdpb;
import org.junit.Assert;
import org.junit.Test;

public class TxnPeerGateTest {

    private static final String OLD = "10.0.0.2:8610";
    private static final String NEW1 = "10.0.0.3:8610";
    private static final String NEW2 = "10.0.0.4:8610";

    private final List<String> configuration = new ArrayList<>(List.of(NEW1, NEW2));
    // What each peer answers: the opcode, null for no answer
    private final Map<String, Byte> answers = new ConcurrentHashMap<>();
    private final List<String> probes = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    private final TxnPeerGate gate = new TxnPeerGate(() -> this.configuration, this::probe,
                                                     3000L, 100L, this.clock::get);

    private CompletableFuture<RaftRpcProcessor.GetMemberResponse> probe(String endpoint) {
        this.probes.add(endpoint);
        Byte op = this.answers.get(endpoint);
        if (op == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("connect refused"));
        }
        RaftRpcProcessor.GetMemberResponse response = new RaftRpcProcessor.GetMemberResponse();
        response.setMaxKvOp(op);
        return CompletableFuture.completedFuture(response);
    }

    @Test
    public void testOldPeerBlocksAndNewPeersAllow() throws PDException {
        this.answers.put(NEW1, KVOperation.TXN);
        this.answers.put(NEW2, KVOperation.TXN);
        this.gate.check();
        Assert.assertEquals(List.of(NEW1, NEW2), this.probes);

        // An old peer joins: it answers as a release before the TXN entry does
        this.configuration.add(OLD);
        this.answers.put(OLD, (byte) 0);
        PDException e = Assert.assertThrows(PDException.class, this.gate::check);
        Assert.assertEquals(Pdpb.ErrorType.TXN_NOT_PROPOSED_VALUE, e.getErrorCode());
        Assert.assertTrue(e.getMessage(), e.getMessage().contains(OLD));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("without the txn RPC"));
        // Only the unconfirmed peer was probed
        Assert.assertEquals(List.of(NEW1, NEW2, OLD), this.probes);

        // Upgraded: it answers with the opcode and is confirmed
        this.answers.put(OLD, KVOperation.TXN);
        this.clock.addAndGet(3000L);
        this.gate.check();
        Assert.assertEquals(List.of(OLD, NEW1, NEW2),
                            this.gate.confirmed().stream().sorted().toList());
    }

    @Test
    public void testConfirmedPeerThatGoesUnreachableDoesNotBlock() throws PDException {
        this.answers.put(NEW1, KVOperation.TXN);
        this.answers.put(NEW2, KVOperation.TXN);
        this.gate.check();
        this.answers.remove(NEW2);
        this.clock.addAndGet(60_000L);
        this.gate.check();
        // Confirmed peers are not probed again
        Assert.assertEquals(List.of(NEW1, NEW2), this.probes);
    }

    @Test
    public void testPeerAddedToTheConfigurationBlocksUntilItAnswers() throws PDException {
        this.answers.put(NEW1, KVOperation.TXN);
        this.answers.put(NEW2, KVOperation.TXN);
        this.gate.check();

        // Added, down: probed at once on the configuration change, and it blocks
        String added = "10.0.0.5:8610";
        this.configuration.add(added);
        PDException e = Assert.assertThrows(PDException.class, this.gate::check);
        Assert.assertTrue(e.getMessage(), e.getMessage().contains(added));
        Assert.assertTrue(e.getMessage(), e.getMessage().contains("did not answer"));
        Assert.assertEquals(List.of(NEW1, NEW2, added), this.probes);

        // Within the interval the probe is not repeated, and it still blocks
        this.clock.addAndGet(1000L);
        Assert.assertThrows(PDException.class, this.gate::check);
        Assert.assertEquals(3, this.probes.size());

        // Up and answering after the interval: confirmed
        this.answers.put(added, KVOperation.TXN);
        this.clock.addAndGet(3000L);
        this.gate.check();
        Assert.assertEquals(4, this.probes.size());
        Assert.assertTrue(this.gate.confirmed().contains(added));
    }

    @Test
    public void testProbeIsNotRepeatedOnceConfirmed() throws PDException {
        this.answers.put(NEW1, KVOperation.TXN);
        this.answers.put(NEW2, KVOperation.TXN);
        for (int i = 0; i < 5; i++) {
            this.clock.addAndGet(10_000L);
            this.gate.check();
        }
        Assert.assertEquals(List.of(NEW1, NEW2), this.probes);
    }

    /**
     * A membership change probes every unconfirmed peer of the new configuration at once,
     * whatever the interval, and is refused by an older or silent one
     */
    @Test
    public void testMembershipChangeProbesTheNewConfigurationAtOnce() throws PDException {
        this.answers.put(NEW1, KVOperation.TXN);
        this.answers.put(NEW2, KVOperation.TXN);
        this.gate.check();

        this.answers.put(OLD, (byte) 0);
        PDException old = Assert.assertThrows(PDException.class,
                                              () -> this.gate.confirm(List.of(NEW1, NEW2, OLD)));
        Assert.assertEquals(Pdpb.ErrorType.TXN_NOT_PROPOSED_VALUE, old.getErrorCode());
        Assert.assertTrue(old.getMessage(), old.getMessage().contains("membership change"));
        Assert.assertTrue(old.getMessage(), old.getMessage().contains(OLD));

        // Within the TXN probe interval: probed again at once, and refused while silent
        String silent = "10.0.0.6:8610";
        Assert.assertThrows(PDException.class,
                            () -> this.gate.confirm(List.of(NEW1, NEW2, silent)));
        Assert.assertEquals(List.of(NEW1, NEW2, OLD, silent), this.probes);

        // A new peer that answers is confirmed, and the TXN check then needs no probe for it
        this.answers.put(silent, KVOperation.TXN);
        this.gate.confirm(List.of(NEW1, NEW2, silent));
        this.configuration.add(silent);
        this.gate.check();
        Assert.assertEquals(List.of(NEW1, NEW2, OLD, silent, silent), this.probes);
    }

    @Test
    public void testNothingToConfirmWithoutPeers() throws PDException {
        this.configuration.clear();
        this.gate.check();
        Assert.assertTrue(this.probes.isEmpty());
    }
}
