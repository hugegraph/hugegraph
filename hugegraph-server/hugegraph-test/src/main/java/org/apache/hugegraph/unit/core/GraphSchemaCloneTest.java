/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hugegraph.unit.core;

import org.apache.hugegraph.HugeGraph;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.exception.HugeException;
import org.apache.hugegraph.schema.SchemaManager;
import org.apache.hugegraph.struct.schema.IndexLabel;
import org.apache.hugegraph.type.define.SchemaStatus;

import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

public class GraphSchemaCloneTest {

    @Test
    public void testCopyFailureReportsPartialTargetWithoutDeletingIt() {
        GraphManager manager = Mockito.mock(GraphManager.class, Mockito.CALLS_REAL_METHODS);
        HugeGraph source = Mockito.mock(HugeGraph.class);
        HugeGraph target = Mockito.mock(HugeGraph.class);
        SchemaManager sourceSchema = Mockito.mock(SchemaManager.class);
        SchemaManager targetSchema = Mockito.mock(SchemaManager.class);
        Mockito.doReturn(source).when(manager).graph("DEFAULT-source");
        Mockito.doReturn(target).when(manager).cloneGraph(Mockito.eq("DEFAULT"),
                          Mockito.eq("source"), Mockito.eq("target"), Mockito.anyMap());
        Mockito.when(source.schema()).thenReturn(sourceSchema);
        Mockito.when(target.schema()).thenReturn(targetSchema);
        Mockito.doThrow(new IllegalStateException("copy failed"))
               .when(targetSchema).copyFrom(sourceSchema);

        HugeException failure = Assert.assertThrows(HugeException.class,
                () -> manager.cloneGraphSchema("DEFAULT", "source", "target", "copy", "admin"));
        Assert.assertTrue(failure.getMessage().contains("partially created"));
        Mockito.verify(manager, Mockito.never()).dropGraph(Mockito.anyString(),
                                                          Mockito.anyString(), Mockito.anyBoolean());
    }
    @Test
    public void testFailedIndexCannotProduceSuccessfulClone() {
        GraphManager manager = Mockito.mock(GraphManager.class, Mockito.CALLS_REAL_METHODS);
        HugeGraph source = Mockito.mock(HugeGraph.class);
        HugeGraph target = Mockito.mock(HugeGraph.class);
        SchemaManager targetSchema = Mockito.mock(SchemaManager.class);
        IndexLabel index = Mockito.mock(IndexLabel.class);
        Mockito.doReturn(source).when(manager).graph("DEFAULT-source");
        Mockito.doReturn(target).when(manager).cloneGraph(Mockito.eq("DEFAULT"),
                          Mockito.eq("source"), Mockito.eq("target"), Mockito.anyMap());
        Mockito.when(source.schema()).thenReturn(Mockito.mock(SchemaManager.class));
        Mockito.when(target.schema()).thenReturn(targetSchema);
        Mockito.when(targetSchema.getIndexLabels()).thenReturn(ImmutableList.of(index));
        Mockito.when(index.status()).thenReturn(SchemaStatus.INVALID);

        HugeException failure = Assert.assertThrows(HugeException.class,
                () -> manager.cloneGraphSchema("DEFAULT", "source", "target", "copy", "admin"));
        Assert.assertTrue(failure.getMessage().contains("partially created"));
        Assert.assertNull(failure.getCause());
    }

}
