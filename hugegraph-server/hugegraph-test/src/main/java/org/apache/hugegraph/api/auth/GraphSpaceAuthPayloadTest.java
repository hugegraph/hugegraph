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

package org.apache.hugegraph.api.auth;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hugegraph.auth.AuthManager;
import org.apache.hugegraph.auth.HugeGraphAuthProxy;
import org.apache.hugegraph.api.filter.PathFilter;
import org.apache.hugegraph.core.GraphManager;
import org.apache.hugegraph.config.HugeConfig;
import org.apache.hugegraph.config.ServerOptions;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.glassfish.jersey.internal.MapPropertiesDelegate;
import org.glassfish.jersey.server.ContainerRequest;
import org.apache.hugegraph.auth.HugeAccess;
import org.apache.hugegraph.auth.HugeBelong;
import org.apache.hugegraph.auth.HugeGroup;
import org.apache.hugegraph.auth.HugePermission;
import org.apache.hugegraph.auth.HugeTarget;
import org.apache.hugegraph.auth.HugeUser;
import org.apache.hugegraph.backend.id.IdGenerator;
import org.apache.hugegraph.testutil.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ForbiddenException;
import jakarta.inject.Provider;

public class GraphSpaceAuthPayloadTest {

    @Test
    public void testTargetPayloadUsesPathGraphSpaceAndOptionalUrl()
            throws Exception {
        TargetAPI.JsonTarget jsonTarget = new ObjectMapper().readValue(
                "{\"target_name\":\"target\"," +
                "\"target_graph\":\"hugegraph\"," +
                "\"target_description\":\"description\"," +
                "\"target_resources\":[]}",
        TargetAPI.JsonTarget.class);

        HugeTarget target = jsonTarget.build("SPACE_A");
        target.creator("manager");
        Map<String, Object> properties = target.asMap();

        Assert.assertEquals("SPACE_A", target.graphSpace());
        Assert.assertEquals("description", target.description());
        Assert.assertEquals("", target.url());
        Assert.assertEquals("SPACE_A", properties.get("graphspace"));
        Assert.assertEquals("description",
                            properties.get("target_description"));
    }

    @Test
    public void testRelationshipPayloadsUsePathGraphSpace() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AccessAPI.JsonAccess jsonAccess = mapper.readValue(
                "{\"group\":\"group\",\"target\":\"target\"," +
                "\"access_permission\":\"READ\"}",
                AccessAPI.JsonAccess.class);
        HugeAccess access = jsonAccess.build("SPACE_A");

        Assert.assertEquals("SPACE_A", access.graphSpace());
        Assert.assertEquals(HugePermission.READ, access.permission());

        BelongAPI.JsonBelong jsonBelong = mapper.readValue(
                "{\"user\":\"user\",\"group\":\"group\"}",
                BelongAPI.JsonBelong.class);
        HugeBelong belong = jsonBelong.build("SPACE_A");

        Assert.assertEquals("SPACE_A", belong.graphSpace());
        Assert.assertEquals(HugeBelong.UG, belong.link());
    }

    @Test
    public void testTargetRejectsForeignGraphSpace() {
        HugeTarget target = new HugeTarget("target", "hugegraph", "");
        target.graphSpace("SPACE_A");

        TargetAPI.checkGraphSpace("SPACE_A", target);
        Assert.assertThrows(ForbiddenException.class, () ->
                TargetAPI.checkGraphSpace("SPACE_B", target));
    }

    @Test
    public void testAccessRejectsForeignGraphSpace() {
        HugeAccess access = new HugeAccess("SPACE_A",
                                           IdGenerator.of("group"),
                                           IdGenerator.of("target"));

        AccessAPI.checkGraphSpace("SPACE_A", access);
        Assert.assertThrows(ForbiddenException.class, () ->
                AccessAPI.checkGraphSpace("SPACE_B", access));
    }

    @Test
    public void testCreateAccessRejectsForeignTargetWithoutSideEffects()
            throws Exception {
        AuthManager auth = Mockito.mock(AuthManager.class);
        AccessAPI.JsonAccess jsonAccess = new ObjectMapper().readValue(
                "{\"group\":\"group\",\"target\":\"target\"," +
                "\"access_permission\":\"READ\"}",
                AccessAPI.JsonAccess.class);
        HugeAccess access = jsonAccess.build("SPACE_A");
        HugeTarget target = new HugeTarget("target", "hugegraph", "");
        target.graphSpace("SPACE_B");
        Mockito.when(auth.getTarget("SPACE_A", access.target()))
               .thenReturn(target);

        Assert.assertThrows(ForbiddenException.class, () ->
                AccessAPI.createScopedAccess(auth, "SPACE_A", access));

        Mockito.verify(auth).getTarget("SPACE_A", access.target());
        Mockito.verify(auth, Mockito.never())
               .createAccess(Mockito.anyString(),
                             Mockito.any(HugeAccess.class));
    }

    @Test
    public void testScopedGroupPayloadCanBeUsedToCreateAccess() throws Exception {
        AuthManager auth = Mockito.mock(AuthManager.class);
        Mockito.when(auth.supportsGraphSpaceAuth()).thenReturn(true);
        ObjectMapper mapper = new ObjectMapper();
        GraphSpaceGroupAPI.JsonGroup jsonGroup = mapper.readValue(
                "{\"group_name\":\"client-group\"}", GraphSpaceGroupAPI.JsonGroup.class);
        HugeGroup group = jsonGroup.build("SPACE_A");
        AccessAPI.JsonAccess jsonAccess = mapper.readValue(
                "{\"group\":\"group-id\",\"target\":\"target\",\"access_permission\":\"READ\"}",
                AccessAPI.JsonAccess.class);
        HugeAccess access = jsonAccess.build("SPACE_A");
        HugeTarget target = new HugeTarget("target", "hugegraph", "");
        target.graphSpace("SPACE_A");
        Mockito.when(auth.getGroup(access.source())).thenReturn(group);
        Mockito.when(auth.getTarget("SPACE_A", access.target())).thenReturn(target);
        Mockito.when(auth.createAccess("SPACE_A", access)).thenReturn(IdGenerator.of("access-id"));

        Assert.assertEquals(IdGenerator.of("access-id"),
                            AccessAPI.createScopedAccess(auth, "SPACE_A", access));
        Mockito.verify(auth).createAccess("SPACE_A", access);
    }

    @Test
    public void testCreateAccessRejectsBuiltInRoleGroup() throws Exception {
        AuthManager auth = Mockito.mock(AuthManager.class);
        Mockito.when(auth.supportsGraphSpaceAuth()).thenReturn(true);
        AccessAPI.JsonAccess jsonAccess = new ObjectMapper().readValue(
                "{\"group\":\"builtin\",\"target\":\"target\"," +
                "\"access_permission\":\"READ\"}",
                AccessAPI.JsonAccess.class);
        HugeAccess access = jsonAccess.build("SPACE_A");
        Mockito.when(auth.getGroup(access.source())).thenReturn(null);

        Assert.assertThrows(ForbiddenException.class, () ->
                AccessAPI.createScopedAccess(auth, "SPACE_A", access));

        Mockito.verify(auth, Mockito.never())
               .createAccess(Mockito.anyString(),
                             Mockito.any(HugeAccess.class));
    }

    @Test
    public void testAccessListHidesBuiltInRoleAccesses() {
        AuthManager auth = Mockito.mock(AuthManager.class);
        Mockito.when(auth.supportsGraphSpaceAuth()).thenReturn(true);
        HugeAccess builtIn = access("SPACE_A", "builtin");
        HugeAccess business = access("SPACE_A", "business");
        Mockito.when(auth.listAllAccess("SPACE_A", -1L)).thenReturn(
                Arrays.asList(builtIn, business));
        Mockito.when(auth.getGroup(builtIn.source())).thenReturn(null);
        Mockito.when(auth.getGroup(business.source())).thenReturn(
                new HugeGroup(GraphSpaceGroupAPI.scopedPrefix("SPACE_A") +
                              "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));

        List<HugeAccess> accesses = AccessAPI.listScopedAccesses(
                                    auth, "SPACE_A", null, null, 100L);

        Assert.assertEquals(Arrays.asList(business), accesses);
    }

    @Test
    public void testBelongRejectsForeignGraphSpace() {
        HugeBelong belong = new HugeBelong("SPACE_A",
                                           IdGenerator.of("user"),
                                           IdGenerator.of("group"),
                                           null, HugeBelong.UG);

        BelongAPI.checkGraphSpace("SPACE_A", belong);
        Assert.assertThrows(ForbiddenException.class, () ->
                BelongAPI.checkGraphSpace("SPACE_B", belong));
    }

    @Test
    public void testScopedChecksRejectMissingEntities() {
        Assert.assertThrows(IllegalArgumentException.class, () ->
                TargetAPI.checkGraphSpace("SPACE_A", null));
        Assert.assertThrows(IllegalArgumentException.class, () ->
                BelongAPI.checkGraphSpace("SPACE_A", null));
        Assert.assertThrows(IllegalArgumentException.class, () ->
                AccessAPI.checkGraphSpace("SPACE_A", null));
    }

    @Test
    public void testScopedListsFilterBeforeApplyingLimit() {
        AuthManager auth = Mockito.mock(AuthManager.class);
        HugeTarget foreignTarget = target("SPACE_B", "foreign");
        HugeTarget ownTarget1 = target("SPACE_A", "own-1");
        HugeTarget ownTarget2 = target("SPACE_A", "own-2");
        Mockito.when(auth.listAllTargets("SPACE_A", -1L)).thenReturn(
                Arrays.asList(foreignTarget, ownTarget1, ownTarget2));

        HugeBelong foreignBelong = belong("SPACE_B", "foreign");
        HugeBelong ownBelong1 = belong("SPACE_A", "own-1");
        HugeBelong ownBelong2 = belong("SPACE_A", "own-2");
        Mockito.when(auth.listAllBelong("SPACE_A", -1L)).thenReturn(
                Arrays.asList(foreignBelong, ownBelong1, ownBelong2));

        HugeAccess foreignAccess = access("SPACE_B", "foreign");
        HugeAccess ownAccess1 = access("SPACE_A", "own-1");
        HugeAccess ownAccess2 = access("SPACE_A", "own-2");
        Mockito.when(auth.listAllAccess("SPACE_A", -1L)).thenReturn(
                Arrays.asList(foreignAccess, ownAccess1, ownAccess2));

        List<HugeTarget> targets = TargetAPI.listScopedTargets(
                                   auth, "SPACE_A", 1L);
        List<HugeBelong> belongs = BelongAPI.listScopedBelongs(
                                   auth, "SPACE_A", null, null, 1L);
        List<HugeAccess> accesses = AccessAPI.listScopedAccesses(
                                   auth, "SPACE_A", null, null, 1L);

        Assert.assertEquals(Arrays.asList(ownTarget1), targets);
        Assert.assertEquals(Arrays.asList(ownBelong1), belongs);
        Assert.assertEquals(Arrays.asList(ownAccess1), accesses);
    }

    private static HugeTarget target(String graphSpace, String name) {
        HugeTarget target = new HugeTarget(name, "hugegraph", "");
        target.graphSpace(graphSpace);
        target.creator("admin");
        return target;
    }

    private static HugeBelong belong(String graphSpace, String suffix) {
        HugeBelong belong = new HugeBelong(
                graphSpace, IdGenerator.of("user-" + suffix),
                IdGenerator.of("group-" + suffix), null, HugeBelong.UG);
        belong.creator("admin");
        return belong;
    }

    private static HugeAccess access(String graphSpace, String suffix) {
        HugeAccess access = new HugeAccess(
                graphSpace, IdGenerator.of("group-" + suffix),
                IdGenerator.of("target-" + suffix));
        access.creator("admin");
        return access;
    }
    @Test
    public void testLegacyBelongEnrollsUserBeforeCreatingRelation() {
        for (String space : List.of("DEFAULT", "SPACE_A")) {
            for (boolean admin : List.of(false, true)) {
                AuthManager auth = legacyAuth(space);
                Mockito.when(auth.isAdminManager("operator")).thenReturn(admin);
                Mockito.when(auth.isSpaceManager(space, "operator")).thenReturn(!admin);
                AtomicBoolean member = new AtomicBoolean();
                Mockito.when(auth.isSpaceMember(space, "alice")).thenAnswer(call -> member.get());
                Mockito.when(auth.createSpaceMember(space, "alice")).thenAnswer(call -> {
                    member.set(true);
                    return IdGenerator.of("membership");
                });
                HugeBelong belong = userBelong(space);
                Mockito.when(auth.createBelong(space, belong)).thenReturn(IdGenerator.of("relation"));
                Assert.assertEquals(IdGenerator.of("relation"),
                                    BelongAPI.createCompatibleBelong(auth, space, belong, true, "operator"));
                org.mockito.InOrder order = Mockito.inOrder(auth);
                order.verify(auth).createSpaceMember(space, "alice");
                order.verify(auth).createBelong(space, belong);
            }
        }
    }

    @Test
    public void testLegacyRequestMarkerReachesBelongEndpoint() throws Exception {
        AuthManager auth = legacyAuth("SPACE_A");
        Mockito.when(auth.isAdminManager(HugeGraphAuthProxy.username())).thenReturn(true);
        AtomicBoolean member = new AtomicBoolean();
        Mockito.when(auth.isSpaceMember("SPACE_A", "alice")).thenAnswer(call -> member.get());
        Mockito.when(auth.createSpaceMember("SPACE_A", "alice")).thenAnswer(call -> {
            member.set(true);
            return IdGenerator.of("membership");
        });
        Mockito.when(auth.createBelong(Mockito.eq("SPACE_A"), Mockito.any(HugeBelong.class)))
               .thenReturn(IdGenerator.of("relation"));
        GraphManager manager = Mockito.mock(GraphManager.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(manager.authManager()).thenReturn(auth);
        PropertiesConfiguration settings = new PropertiesConfiguration();
        settings.setProperty(ServerOptions.PATH_GRAPH_SPACE.name(), "SPACE_A");
        HugeConfig config = new HugeConfig(settings);
        PathFilter filter = new PathFilter();
        java.lang.reflect.Field provider = PathFilter.class.getDeclaredField("configProvider");
        provider.setAccessible(true);
        provider.set(filter, (Provider<HugeConfig>) () -> config);
        URI base = URI.create("http://localhost:8080/");
        ContainerRequest request = new ContainerRequest(base, base.resolve("graphs/hugegraph/auth/belongs"),
                                                       "POST", null, new MapPropertiesDelegate());
        filter.filter(request);
        Assert.assertEquals("graphspaces/SPACE_A/auth/belongs", request.getUriInfo().getPath());
        BelongAPI.JsonBelong payload = new ObjectMapper().readValue(
                "{\"user\":\"alice\",\"group\":\"group\"}", BelongAPI.JsonBelong.class);
        new BelongAPI().create(manager, request, "SPACE_A", payload);
        Mockito.verify(auth).createSpaceMember("SPACE_A", "alice");
        Mockito.verify(auth).createBelong(Mockito.eq("SPACE_A"), Mockito.any(HugeBelong.class));
    }

    @Test
    public void testExplicitScopedBelongStillRequiresMembership() {
        AuthManager auth = legacyAuth("SPACE_A");
        Assert.assertThrows(ForbiddenException.class, () ->
                BelongAPI.createCompatibleBelong(auth, "SPACE_A", userBelong("SPACE_A"), false, "operator"));
        Mockito.verify(auth, Mockito.never()).createSpaceMember(Mockito.anyString(), Mockito.anyString());
        Mockito.verify(auth, Mockito.never()).createBelong(Mockito.anyString(), Mockito.any(HugeBelong.class));
    }

    @Test
    public void testLegacyEnrollmentRequiresManagerAndScopedGroup() {
        AuthManager auth = legacyAuth("SPACE_A");
        Mockito.when(auth.isAdminManager("operator")).thenReturn(false);
        Assert.assertThrows(ForbiddenException.class, () ->
                BelongAPI.createCompatibleBelong(auth, "SPACE_A", userBelong("SPACE_A"), true, "operator"));
        Mockito.verify(auth, Mockito.never()).createSpaceMember(Mockito.anyString(), Mockito.anyString());
        Mockito.when(auth.isAdminManager("operator")).thenReturn(true);
        Mockito.when(auth.getGroup(IdGenerator.of("group"))).thenReturn(
                new HugeGroup(GraphSpaceGroupAPI.scopedPrefix("SPACE_B") + "0123456789abcdef0123456789abcdef"));
        Assert.assertThrows(ForbiddenException.class, () ->
                BelongAPI.createCompatibleBelong(auth, "SPACE_A", userBelong("SPACE_A"), true, "operator"));
        Mockito.when(auth.getGroup(IdGenerator.of("group"))).thenReturn(null);
        Assert.assertThrows(ForbiddenException.class, () ->
                BelongAPI.createCompatibleBelong(auth, "SPACE_A", userBelong("SPACE_A"), true, "operator"));
        Mockito.verify(auth, Mockito.never()).createSpaceMember(Mockito.anyString(), Mockito.anyString());
    }

    @Test
    public void testLegacyExistingRolesAndV1DoNotEnrollAgain() {
        for (String role : List.of("admin", "manager", "member", "v1")) {
            AuthManager auth = legacyAuth("SPACE_A");
            Mockito.when(auth.isAdminManager("alice")).thenReturn(role.equals("admin"));
            Mockito.when(auth.isSpaceManager("SPACE_A", "alice")).thenReturn(role.equals("manager"));
            Mockito.when(auth.isSpaceMember("SPACE_A", "alice")).thenReturn(role.equals("member"));
            Mockito.when(auth.supportsGraphSpaceAuth()).thenReturn(!role.equals("v1"));
            HugeBelong belong = userBelong("SPACE_A");
            BelongAPI.createCompatibleBelong(auth, "SPACE_A", belong, true, "operator");
            Mockito.verify(auth, Mockito.never()).createSpaceMember(Mockito.anyString(), Mockito.anyString());
            Mockito.verify(auth).createBelong("SPACE_A", belong);
        }
    }

    @Test
    public void testFailedEnrollmentDoesNotCreateRelation() {
        AuthManager auth = legacyAuth("SPACE_A");
        Mockito.when(auth.createSpaceMember("SPACE_A", "alice"))
               .thenThrow(new IllegalStateException("membership write failed"));
        Assert.assertThrows(IllegalStateException.class, () ->
                BelongAPI.createCompatibleBelong(auth, "SPACE_A", userBelong("SPACE_A"), true, "operator"));
        Mockito.verify(auth, Mockito.never()).createBelong(Mockito.anyString(), Mockito.any(HugeBelong.class));
    }

    @Test
    public void testFailedRelationCanRetryWithoutEnrollingAgain() {
        AuthManager auth = legacyAuth("SPACE_A");
        AtomicBoolean member = new AtomicBoolean();
        Mockito.when(auth.isSpaceMember("SPACE_A", "alice")).thenAnswer(call -> member.get());
        Mockito.when(auth.createSpaceMember("SPACE_A", "alice")).thenAnswer(call -> {
            member.set(true);
            return IdGenerator.of("membership");
        });
        HugeBelong belong = userBelong("SPACE_A");
        Mockito.when(auth.createBelong("SPACE_A", belong))
               .thenThrow(new IllegalStateException("relation write failed"))
               .thenReturn(IdGenerator.of("relation"));
        Assert.assertThrows(IllegalStateException.class, () ->
                BelongAPI.createCompatibleBelong(auth, "SPACE_A", belong, true, "operator"));
        Assert.assertEquals(IdGenerator.of("relation"),
                            BelongAPI.createCompatibleBelong(auth, "SPACE_A", belong, true, "operator"));
        Mockito.verify(auth, Mockito.times(1)).createSpaceMember("SPACE_A", "alice");
        Mockito.verify(auth, Mockito.never()).deleteSpaceMember(Mockito.anyString(), Mockito.anyString());
    }

    private static HugeBelong userBelong(String space) {
        return new HugeBelong(space, IdGenerator.of("alice"), IdGenerator.of("group"), null, HugeBelong.UG);
    }

    private static AuthManager legacyAuth(String space) {
        AuthManager auth = Mockito.mock(AuthManager.class);
        Mockito.when(auth.supportsGraphSpaceAuth()).thenReturn(true);
        Mockito.when(auth.isAdminManager("operator")).thenReturn(true);
        Mockito.when(auth.findUser("alice")).thenReturn(new HugeUser("alice"));
        Mockito.when(auth.getGroup(IdGenerator.of("group"))).thenReturn(
                new HugeGroup(GraphSpaceGroupAPI.scopedPrefix(space) + "0123456789abcdef0123456789abcdef"));
        return auth;
    }

}
