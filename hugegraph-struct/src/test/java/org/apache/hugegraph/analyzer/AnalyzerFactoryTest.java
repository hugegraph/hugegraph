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

package org.apache.hugegraph.analyzer;

import org.apache.hugegraph.exception.HugeException;
import org.junit.Assert;
import org.junit.Test;

public class AnalyzerFactoryTest {

    @Test
    public void testWordAnalyzerIsNotBundled() {
        ClassLoader loader = AnalyzerFactory.class.getClassLoader();
        Assert.assertNull(loader.getResource("org/apdplat/word/WordSegmenter.class"));
        Assert.assertNull(loader.getResource("org/apache/hugegraph/analyzer/WordAnalyzer.class"));
        HugeException exception = Assert.assertThrows(HugeException.class,
                () -> AnalyzerFactory.analyzer("word", "MaximumMatching"));
        Assert.assertEquals("Not exists analyzer: word", exception.getMessage());
    }

    @Test
    public void testSmartCnUsesCompatibleLuceneDependencies() {
        Analyzer analyzer = AnalyzerFactory.analyzer("smartcn", "");
        Assert.assertTrue(analyzer.segment("HugeGraph graph database").contains("hugegraph"));
    }

    @Test
    public void testDefaultAnalyzerStillSegmentsText() {
        Analyzer analyzer = AnalyzerFactory.analyzer("ikanalyzer", "smart");
        Assert.assertTrue(analyzer.segment("HugeGraph graph database").contains("hugegraph"));
    }
}
