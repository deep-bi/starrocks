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

import com.starrocks.authentication.AuthenticationException;
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
            }
        };
        manager = new RoleConnectionManager(100, "role_a:1,role_b:1");
    }

    private ConnectContext newContext(long roleId) {
        ConnectContext ctx = new ConnectContext();
        ctx.setGlobalStateMgr(globalStateMgr);
        ctx.setQualifiedUser("u");
        ctx.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("u", "%"));
        ctx.setCurrentRoleIds(Set.of(roleId));
        return ctx;
    }

    @Test
    public void testRejectedRegistrationIsNotCounted() throws Exception {
        ConnectContext first = newContext(ROLE_A);
        manager.registerConnection(first);
        Assertions.assertThrows(AuthenticationException.class, () -> manager.registerConnection(newContext(ROLE_A)));

        // had the rejection been counted, role_a would still be at its limit after the first session closes
        manager.unregisterConnection(first);
        manager.registerConnection(newContext(ROLE_A));
    }

    @Test
    public void testRepeatedSessionsUnderLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            ConnectContext ctx = newContext(ROLE_A);
            manager.registerConnection(ctx);
            manager.unregisterConnection(ctx);
        }
    }

    @Test
    public void testRejectedUserSwitchKeepsPreviousCount() throws Exception {
        manager.registerConnection(newContext(ROLE_B));
        ConnectContext ctx = newContext(ROLE_A);
        manager.registerConnection(ctx);

        ctx.setCurrentRoleIds(Set.of(ROLE_B));
        Assertions.assertThrows(AuthenticationException.class, () -> manager.registerConnection(ctx));
        Assertions.assertThrows(AuthenticationException.class, () -> manager.registerConnection(newContext(ROLE_A)));
    }

    @Test
    public void testNoLimitsConfigured() throws Exception {
        RoleConnectionManager unlimited = new RoleConnectionManager(1, "");
        unlimited.registerConnection(newContext(ROLE_A));
        unlimited.registerConnection(newContext(ROLE_A));
    }
}
