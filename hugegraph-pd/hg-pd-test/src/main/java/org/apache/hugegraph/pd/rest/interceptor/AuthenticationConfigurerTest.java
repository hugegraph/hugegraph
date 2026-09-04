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

package org.apache.hugegraph.pd.rest.interceptor;

import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import static org.mockito.Mockito.mock;

/**
 * The probe endpoints have to stay outside the auth interceptor. If one of them slips back
 * behind it, PD answers a probe with 200 and an error envelope instead of a readiness
 * answer, so every healthcheck gating on the body holds forever while the status still
 * looks fine. That failure is silent, hence a check here rather than only in the live
 * REST suite.
 */
public class AuthenticationConfigurerTest {

    private static final PathMatcher MATCHER = new AntPathMatcher();

    /**
     * {@code InterceptorRegistry.getInterceptors()} is protected, so read it from a subclass.
     */
    private static final class TestRegistry extends InterceptorRegistry {

        List<Object> registered() {
            return getInterceptors();
        }
    }

    private static MappedInterceptor authInterceptor() {
        AuthenticationConfigurer configurer = new AuthenticationConfigurer();
        configurer.restAuthentication = mock(RestAuthentication.class);

        TestRegistry registry = new TestRegistry();
        configurer.addInterceptors(registry);

        List<Object> registered = registry.registered();
        Assert.assertEquals(1, registered.size());
        return (MappedInterceptor) registered.get(0);
    }

    @Test
    public void testProbeEndpointsAreAnonymous() {
        // A kubelet probe and a compose healthcheck cannot present credentials
        MappedInterceptor auth = authInterceptor();
        Assert.assertFalse("/v1/ready must not be intercepted",
                           auth.matches("/v1/ready", MATCHER));
        Assert.assertFalse("/v1/health must not be intercepted",
                           auth.matches("/v1/health", MATCHER));
        Assert.assertFalse("/actuator/prometheus must not be intercepted",
                           auth.matches("/actuator/prometheus", MATCHER));
    }

    @Test
    public void testEverythingElseStaysAuthenticated() {
        MappedInterceptor auth = authInterceptor();
        Assert.assertTrue("/v1/members carries cluster addresses and must stay authenticated",
                          auth.matches("/v1/members", MATCHER));
        Assert.assertTrue(auth.matches("/v1/stores", MATCHER));
        Assert.assertTrue(auth.matches("/v1/members/change", MATCHER));
    }
}
