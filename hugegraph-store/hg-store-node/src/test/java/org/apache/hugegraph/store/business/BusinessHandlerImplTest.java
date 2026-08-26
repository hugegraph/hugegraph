/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.store.business;

import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest;
import org.apache.hugegraph.store.grpc.Graphpb.ScanPartitionRequest.Request;
import org.apache.hugegraph.store.meta.PartitionManager;
import org.apache.hugegraph.store.util.HgStoreException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class BusinessHandlerImplTest {

    @Test
    public void testRejectNonEmptyConditionBeforeStoreScan() {
        PartitionManager partitionManager =
                Mockito.mock(PartitionManager.class);
        BusinessHandlerImpl handler =
                new BusinessHandlerImpl(partitionManager);
        Mockito.clearInvocations(partitionManager);
        Request request = Request.newBuilder()
                                 .setCondition("element.name() == 'marko'")
                                 .build();
        ScanPartitionRequest scanRequest =
                ScanPartitionRequest.newBuilder()
                                    .setScanRequest(request)
                                    .build();

        HgStoreException exception = Assertions.assertThrows(
                HgStoreException.class, () -> handler.scan(scanRequest));

        Assertions.assertTrue(exception.getMessage().contains("condition"));
        Mockito.verifyNoInteractions(partitionManager);
    }
}
