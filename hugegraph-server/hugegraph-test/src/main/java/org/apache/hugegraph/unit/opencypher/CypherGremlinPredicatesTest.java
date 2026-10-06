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

package org.apache.hugegraph.unit.opencypher;

import org.apache.hugegraph.opencypher.CypherGremlinPredicates;
import org.apache.hugegraph.testutil.Assert;
import org.apache.hugegraph.unit.BaseUnitTest;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceEdge;
import org.apache.tinkerpop.gremlin.structure.util.reference.ReferenceVertex;
import org.junit.Test;

public class CypherGremlinPredicatesTest extends BaseUnitTest {

    @Test
    public void testRegexUsesWholeStringMatching() {
        CypherGremlinPredicates predicates = new CypherGremlinPredicates();
        Assert.assertTrue(predicates.regexMatch("m.*o").test("marko"));
        Assert.assertFalse(predicates.regexMatch("ark").test("marko"));
        Assert.assertTrue(predicates.regexMatch("marko").test("marko"));
        Assert.assertFalse(predicates.regexMatch("marko").negate().test("marko"));
        Assert.assertTrue(predicates.regexMatch("marko").negate().test("peter"));
    }

    @Test
    public void testNodeRelationshipAndStringPredicates() {
        CypherGremlinPredicates predicates = new CypherGremlinPredicates();
        ReferenceVertex source = new ReferenceVertex("source", "person");
        ReferenceVertex target = new ReferenceVertex("target", "person");
        ReferenceEdge edge = new ReferenceEdge("edge", "knows", source, target);
        Assert.assertTrue(predicates.isNode().test(source));
        Assert.assertFalse(predicates.isNode().test(edge));
        Assert.assertTrue(predicates.isRelationship().test(edge));
        Assert.assertFalse(predicates.isRelationship().test(source));
        Assert.assertTrue(predicates.isString().test("marko"));
        Assert.assertFalse(predicates.isString().test(42));
        Assert.assertFalse(predicates.isString().test(source));
    }
}
