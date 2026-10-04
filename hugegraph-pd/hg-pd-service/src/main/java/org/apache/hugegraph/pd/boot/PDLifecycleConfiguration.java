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

package org.apache.hugegraph.pd.boot;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Service-specific ownership ordering, without requiring service beans in the core module. */
@Configuration
public class PDLifecycleConfiguration {

    @Bean
    public static BeanFactoryPostProcessor metadataOwnerBeforeInitializers() {
        return factory -> {
            for (String name : factory.getBeanDefinitionNames()) {
                BeanDefinition bean = factory.getBeanDefinition(name);
                String type = bean.getBeanClassName();
                // Boot owns the lifecycle and its dependency-free REST gate. All other
                // component-scanned PD beans may acquire metadata or Raft in constructors/init.
                if (type == null || !type.startsWith("org.apache.hugegraph.pd.") ||
                    type.startsWith("org.apache.hugegraph.pd.boot.")) {
                    continue;
                }
                Set<String> dependencies = new LinkedHashSet<>();
                if (bean.getDependsOn() != null) {
                    dependencies.addAll(Arrays.asList(bean.getDependsOn()));
                }
                dependencies.add("pdLifecycle");
                bean.setDependsOn(dependencies.toArray(new String[0]));
            }
        };
    }
}
