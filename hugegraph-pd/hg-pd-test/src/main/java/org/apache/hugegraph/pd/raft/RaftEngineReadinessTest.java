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

import java.util.Arrays;

import org.apache.hugegraph.testutil.Whitebox;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.core.State;
import com.alipay.sofa.jraft.entity.PeerId;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the raft-aware readiness signal behind {@code GET /v1/ready} and the
 * {@code hg.raft.*} gauges: a PD is ready only while it sees a raft leader.
 */
public class RaftEngineReadinessTest {

    private static final PeerId LEADER = new PeerId("10.0.0.1", 8610);
    private static final PeerId SELF = new PeerId("10.0.0.2", 8610);
    private static final PeerId OTHER = new PeerId("10.0.0.3", 8610);

    private Node originalRaftNode;
    private Node mockNode;

    @Before
    public void setUp() {
        RaftEngine engine = RaftEngine.getInstance();
        originalRaftNode = engine.getRaftNode();
        mockNode = mock(Node.class);
        Whitebox.setInternalState(engine, "raftNode", mockNode);
    }

    @After
    public void tearDown() {
        Whitebox.setInternalState(RaftEngine.getInstance(), "raftNode", originalRaftNode);
    }

    private void stub(State state, PeerId leader, boolean isLeader) {
        when(mockNode.getNodeState()).thenReturn(state);
        when(mockNode.getLeaderId()).thenReturn(leader);
        when(mockNode.isLeader(true)).thenReturn(isLeader);
    }

    @Test
    public void testNotReadyBeforeRaftNodeStarts() {
        Whitebox.setInternalState(RaftEngine.getInstance(), "raftNode", null);
        RaftEngine engine = RaftEngine.getInstance();

        Assert.assertFalse(engine.isReady());
        Assert.assertFalse(engine.hasLeader());
        Assert.assertFalse(engine.isLeader());
        Assert.assertNull(engine.getLeader());
        Assert.assertEquals(-1, engine.getAlivePeerCount());

        RaftEngine.RaftStatus status = engine.getRaftStatus();
        Assert.assertFalse(status.isReady());
        Assert.assertFalse(status.isLocalLeader());
        Assert.assertEquals(State.STATE_UNINITIALIZED.name(), status.getState());
    }

    @Test
    public void testLeaderIsReady() {
        stub(State.STATE_LEADER, SELF, true);
        when(mockNode.listAlivePeers()).thenReturn(Arrays.asList(SELF, LEADER, OTHER));
        RaftEngine engine = RaftEngine.getInstance();

        Assert.assertTrue(engine.isReady());
        Assert.assertTrue(engine.hasLeader());
        Assert.assertTrue(engine.isLeader());
        Assert.assertEquals(3, engine.getAlivePeerCount());

        RaftEngine.RaftStatus status = engine.getRaftStatus();
        Assert.assertTrue(status.isReady());
        Assert.assertTrue(status.isLocalLeader());
        Assert.assertEquals(State.STATE_LEADER.name(), status.getState());
    }

    @Test
    public void testFollowerWithLeaderIsReady() {
        stub(State.STATE_FOLLOWER, LEADER, false);
        RaftEngine engine = RaftEngine.getInstance();

        Assert.assertTrue(engine.isReady());
        Assert.assertTrue(engine.hasLeader());
        Assert.assertFalse(engine.isLeader());
        Assert.assertEquals(LEADER, engine.getLeader());
        // Only the leader tracks replication, followers cannot count alive peers
        Assert.assertEquals(-1, engine.getAlivePeerCount());
    }

    @Test
    public void testFollowerWithoutLeaderIsNotReady() {
        // jraft resets the leader id once heartbeats stop arriving inside the election timeout
        stub(State.STATE_FOLLOWER, null, false);
        RaftEngine engine = RaftEngine.getInstance();

        Assert.assertFalse(engine.isReady());
        Assert.assertFalse(engine.hasLeader());
    }

    @Test
    public void testEmptyLeaderIdIsNotReady() {
        // Defensive: NodeImpl.getLeaderId() maps an empty peer to null, so this guards the
        // Node contract rather than a value the real implementation returns
        stub(State.STATE_FOLLOWER, PeerId.emptyPeer(), false);
        RaftEngine engine = RaftEngine.getInstance();

        Assert.assertFalse(engine.isReady());
        Assert.assertFalse(engine.hasLeader());
    }

    @Test
    public void testCandidateWithoutLeaderIsNotReady() {
        // The only candidate shape jraft reaches: NodeImpl clears the leader id before it
        // starts an election, so it is the missing leader, not the state, that holds it back
        stub(State.STATE_CANDIDATE, null, false);
        Assert.assertFalse(RaftEngine.getInstance().isReady());
    }

    @Test
    public void testCandidateCountsAsActive() {
        // Records the scope of the state check: State.isActive() is ordinal() < STATE_ERROR,
        // so a candidate is active and would read as ready if it still knew a leader
        stub(State.STATE_CANDIDATE, LEADER, false);
        Assert.assertTrue(RaftEngine.getInstance().isReady());
    }

    @Test
    public void testTransferringLeaderIsReady() {
        stub(State.STATE_TRANSFERRING, SELF, true);
        Assert.assertTrue(RaftEngine.getInstance().isReady());
    }

    @Test
    public void testInactiveStatesAreNotReadyEvenWithLeaderId() {
        for (State state : new State[]{State.STATE_ERROR, State.STATE_UNINITIALIZED,
                                       State.STATE_SHUTTING, State.STATE_SHUTDOWN}) {
            stub(state, LEADER, false);
            Assert.assertFalse("state " + state + " must not be ready",
                               RaftEngine.getInstance().isReady());
        }
    }

    @Test
    public void testStatusNeverReportsReadyWithoutALeader() {
        // One snapshot, one getLeaderId() read: a step-down cannot yield ready with no leader
        stub(State.STATE_FOLLOWER, null, false);
        RaftEngine.RaftStatus status = RaftEngine.getInstance().getRaftStatus();
        Assert.assertFalse(status.isReady());
        Assert.assertEquals(State.STATE_FOLLOWER.name(), status.getState());
        Assert.assertFalse(status.isLocalLeader());

        stub(State.STATE_FOLLOWER, LEADER, false);
        status = RaftEngine.getInstance().getRaftStatus();
        Assert.assertTrue(status.isReady());
        Assert.assertEquals(State.STATE_FOLLOWER.name(), status.getState());
        Assert.assertFalse(status.isLocalLeader());
    }

    @Test
    public void testAlivePeerCountSurvivesLeadershipLossRace() {
        stub(State.STATE_LEADER, SELF, true);
        when(mockNode.listAlivePeers()).thenThrow(new IllegalStateException("Not leader"));

        Assert.assertEquals(-1, RaftEngine.getInstance().getAlivePeerCount());
    }
}
