// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.qe;

import com.starrocks.authorization.AuthorizationMgr;
import com.starrocks.authorization.RolePrivilegeCollectionV2;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.UserIdentity;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

public class RoleConnectionManagerTest {
    private static final long ROLE_A = 1L;
    private static final long ROLE_B = 2L;
    private static final long ROLE_OTHER = 3L;

    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private AuthorizationMgr authorizationMgr;
    private RoleConnectionManager manager;

    @BeforeEach
    public void setUp() {
        new Expectations() {
            {
                globalStateMgr.getAuthorizationMgr();
                minTimes = 0;
                result = authorizationMgr;

                authorizationMgr.getRolePrivilegeCollection(ROLE_A);
                minTimes = 0;
                result = new RolePrivilegeCollectionV2("role_a");

                authorizationMgr.getRolePrivilegeCollection(ROLE_B);
                minTimes = 0;
                result = new RolePrivilegeCollectionV2("role_b");

                authorizationMgr.getRolePrivilegeCollection(ROLE_OTHER);
                minTimes = 0;
                result = new RolePrivilegeCollectionV2("other");
            }
        };
        manager = new RoleConnectionManager("role_a:1,role_b:1");
    }

    private ConnectContext newContext(Long... roleIds) {
        ConnectContext ctx = new ConnectContext();
        ctx.setGlobalStateMgr(globalStateMgr);
        ctx.setQualifiedUser("u");
        ctx.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("u", "%"));
        ctx.setCurrentRoleIds(Set.of(roleIds));
        return ctx;
    }

    @Test
    public void testRejectedRegistrationIsNotCounted() {
        ConnectContext first = newContext(ROLE_A);
        Assertions.assertTrue(manager.registerConnection(first).first);
        Assertions.assertFalse(manager.registerConnection(newContext(ROLE_A)).first);

        // had the rejection been counted, role_a would still be at its limit after the first session closes
        manager.unregisterConnection(first);
        Assertions.assertTrue(manager.registerConnection(newContext(ROLE_A)).first);
    }

    // the message of ERR_GROUP_MAX_CONNECTION, as released 3.5 builds return it
    @Test
    public void testRejectionNamesTheRole() {
        Assertions.assertTrue(manager.registerConnection(newContext(ROLE_A)).first);
        Assertions.assertEquals("Role-based connection limit exceeded [role=[role_a], maxAllowed=[1], current=[1]]",
                manager.registerConnection(newContext(ROLE_A)).second);
    }

    @Test
    public void testRepeatedSessionsUnderLimit() {
        for (int i = 0; i < 5; i++) {
            ConnectContext ctx = newContext(ROLE_A);
            Assertions.assertTrue(manager.registerConnection(ctx).first);
            manager.unregisterConnection(ctx);
        }
        Assertions.assertEquals(0, manager.getCurrentConnection("role_a"));
    }

    @Test
    public void testRejectedUserSwitchKeepsPreviousCount() {
        Assertions.assertTrue(manager.registerConnection(newContext(ROLE_B)).first);
        ConnectContext ctx = newContext(ROLE_A);
        Assertions.assertTrue(manager.registerConnection(ctx).first);

        ctx.setCurrentRoleIds(Set.of(ROLE_B));
        Assertions.assertFalse(manager.onUserChanged(ctx).first);
        Assertions.assertFalse(manager.registerConnection(newContext(ROLE_A)).first);

        // the connection still holds its role_a slot, and releases it on close
        manager.unregisterConnection(ctx);
        Assertions.assertTrue(manager.registerConnection(newContext(ROLE_A)).first);
        Assertions.assertEquals(1, manager.getCurrentConnection("role_b"));
    }

    @Test
    public void testUserSwitchMovesTheCount() {
        ConnectContext ctx = newContext(ROLE_A);
        Assertions.assertTrue(manager.registerConnection(ctx).first);

        ctx.setCurrentRoleIds(Set.of(ROLE_B));
        Assertions.assertTrue(manager.onUserChanged(ctx).first);
        Assertions.assertEquals(0, manager.getCurrentConnection("role_a"));
        Assertions.assertEquals(1, manager.getCurrentConnection("role_b"));

        manager.unregisterConnection(ctx);
        Assertions.assertEquals(0, manager.getCurrentConnection("role_b"));
    }

    @Test
    public void testUserSwitchKeepingARoleNeedsNoNewSlot() {
        ConnectContext ctx = newContext(ROLE_A);
        Assertions.assertTrue(manager.registerConnection(ctx).first);

        // role_a is at its limit, but this connection already holds the slot
        ctx.setCurrentRoleIds(Set.of(ROLE_A, ROLE_B));
        Assertions.assertTrue(manager.onUserChanged(ctx).first);
        Assertions.assertEquals(1, manager.getCurrentConnection("role_a"));
        Assertions.assertEquals(1, manager.getCurrentConnection("role_b"));
    }

    @Test
    public void testCloseReleasesTheRolesCountedAtLogin() {
        ConnectContext ctx = newContext(ROLE_A);
        Assertions.assertTrue(manager.registerConnection(ctx).first);
        Assertions.assertTrue(manager.registerConnection(newContext(ROLE_B)).first);

        // SET ROLE changes the roles of the session, but not what the connection is counted against
        ctx.setCurrentRoleIds(Set.of(ROLE_B));
        manager.unregisterConnection(ctx);

        Assertions.assertEquals(0, manager.getCurrentConnection("role_a"));
        Assertions.assertEquals(1, manager.getCurrentConnection("role_b"));
        Assertions.assertFalse(manager.registerConnection(newContext(ROLE_B)).first);
    }

    @Test
    public void testConnectionIsCountedOnce() {
        ConnectContext ctx = newContext(ROLE_A);
        Assertions.assertTrue(manager.registerConnection(ctx).first);
        Assertions.assertTrue(manager.registerConnection(ctx).first);
        Assertions.assertEquals(1, manager.getCurrentConnection("role_a"));

        manager.unregisterConnection(ctx);
        manager.unregisterConnection(ctx);
        Assertions.assertEquals(0, manager.getCurrentConnection("role_a"));
    }

    @Test
    public void testUnauthenticatedConnectionIsCountedOnceAuthenticated() {
        ConnectContext ctx = newContext(ROLE_A);
        ctx.setCurrentUserIdentity(null);
        Assertions.assertTrue(manager.registerConnection(ctx).first);
        Assertions.assertEquals(0, manager.getCurrentConnection("role_a"));

        ctx.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("u", "%"));
        Assertions.assertTrue(manager.registerConnection(ctx).first);
        Assertions.assertEquals(1, manager.getCurrentConnection("role_a"));
    }

    @Test
    public void testAnyFullRoleRejectsTheConnection() {
        Assertions.assertTrue(manager.registerConnection(newContext(ROLE_B)).first);
        Assertions.assertFalse(manager.registerConnection(newContext(ROLE_A, ROLE_B)).first);
        Assertions.assertEquals(0, manager.getCurrentConnection("role_a"));
    }

    @Test
    public void testRolesWithoutLimitAreNotCounted() {
        for (int i = 0; i < 3; i++) {
            Assertions.assertTrue(manager.registerConnection(newContext(ROLE_OTHER)).first);
        }
        Assertions.assertEquals(0, manager.getCurrentConnection("other"));
    }

    @Test
    public void testMostSpecificPatternApplies() {
        RoleConnectionManager patterns = new RoleConnectionManager("role_*:2, role_a:1");
        Assertions.assertTrue(patterns.registerConnection(newContext(ROLE_A)).first);
        Assertions.assertFalse(patterns.registerConnection(newContext(ROLE_A)).first);

        Assertions.assertTrue(patterns.registerConnection(newContext(ROLE_B)).first);
        Assertions.assertTrue(patterns.registerConnection(newContext(ROLE_B)).first);
        Assertions.assertFalse(patterns.registerConnection(newContext(ROLE_B)).first);
    }

    @Test
    public void testInvalidConfig() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RoleConnectionManager("role_a"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RoleConnectionManager("role_a:"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RoleConnectionManager(":1"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RoleConnectionManager("role_a:x"));
    }

    @Test
    public void testNoLimitsConfigured() {
        RoleConnectionManager unlimited = new RoleConnectionManager("");
        Assertions.assertTrue(unlimited.registerConnection(newContext(ROLE_A)).first);
        Assertions.assertTrue(unlimited.registerConnection(newContext(ROLE_A)).first);
        Assertions.assertEquals(0, unlimited.getCurrentConnection("role_a"));
    }
}
