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

import com.starrocks.authorization.GrantType;
import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.CreateUserStmt;
import com.starrocks.sql.ast.GrantRoleStmt;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Role-based connection limits as ConnectScheduler applies them to the connections it registers.
 */
public class RoleConnectionLimitTest {
    private static final String LIMITED_ROLE = "rcl_limited";
    private static final String GROUP_ROLE = "rcl_group_role";
    private static final String GROUP = "rcl_analysts";
    private static long limitedRoleId;
    private static int connectionId = 10000;

    @BeforeAll
    public static void beforeClass() throws Exception {
        UtFrameUtils.createMinStarRocksCluster();
        ConnectContext rootCtx = UtFrameUtils.initCtxForNewPrivilege(UserIdentity.ROOT);
        for (String user : List.of("rcl_user1", "rcl_user2")) {
            CreateUserStmt createUserStmt = (CreateUserStmt) UtFrameUtils.parseStmtWithNewParser(
                    "CREATE USER '" + user + "' IDENTIFIED BY ''", rootCtx);
            GlobalStateMgr.getCurrentState().getAuthenticationMgr().createUser(createUserStmt);
        }
        for (String role : List.of(LIMITED_ROLE, GROUP_ROLE)) {
            DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser("CREATE ROLE " + role, rootCtx), rootCtx);
        }
        GrantRoleStmt grantRoleStmt = new GrantRoleStmt(List.of(GROUP_ROLE), GROUP, GrantType.GROUP, NodePosition.ZERO);
        Analyzer.analyze(grantRoleStmt, rootCtx);
        GlobalStateMgr.getCurrentState().getAuthorizationMgr().grantRole(grantRoleStmt);
        limitedRoleId = GlobalStateMgr.getCurrentState().getAuthorizationMgr().getRoleIdByNameAllowNull(LIMITED_ROLE);
    }

    private static ConnectScheduler newScheduler() {
        String limits = Config.qe_role_connection_limits;
        Config.qe_role_connection_limits = LIMITED_ROLE + ":1,rcl_group_*:1";
        try {
            return new ConnectScheduler(100);
        } finally {
            Config.qe_role_connection_limits = limits;
        }
    }

    // a MySQL connection registers once it read the handshake, before it authenticates
    private static ConnectContext newConnection(String user) {
        ConnectContext ctx = new ConnectContext();
        ctx.setGlobalStateMgr(GlobalStateMgr.getCurrentState());
        ctx.setQualifiedUser(user);
        ctx.setConnectionId(connectionId++);
        return ctx;
    }

    private static ConnectContext authenticate(ConnectContext ctx, Set<Long> roleIds) {
        ctx.setCurrentUserIdentity(new UserIdentity(ctx.getQualifiedUser(), "%"));
        ctx.setCurrentRoleIds(roleIds);
        return ctx;
    }

    // the HTTP SQL API and Arrow Flight register a connection after it authenticated
    @Test
    public void testAuthenticatedConnectionIsCountedOnRegistration() {
        ConnectScheduler scheduler = newScheduler();
        ConnectContext first = authenticate(newConnection("rcl_user1"), Set.of(limitedRoleId));
        Assertions.assertTrue(scheduler.registerConnection(first).first);

        ConnectContext second = authenticate(newConnection("rcl_user2"), Set.of(limitedRoleId));
        Pair<Boolean, String> result = scheduler.registerConnection(second);
        Assertions.assertFalse(result.first);
        Assertions.assertTrue(result.second.contains("Role-based connection limit reached"), result.second);
        Assertions.assertNull(scheduler.getContext(second.getConnectionId()));
        Assertions.assertNull(scheduler.getUserConnectionMap().get("rcl_user2"));

        scheduler.unregisterConnection(first);
        Assertions.assertTrue(scheduler.registerConnection(second).first);
    }

    @Test
    public void testMysqlConnectionIsCountedOnceAuthenticated() {
        ConnectScheduler scheduler = newScheduler();
        RoleConnectionManager roles = scheduler.getRoleConnectionManager();

        ConnectContext first = newConnection("rcl_user1");
        Assertions.assertTrue(scheduler.registerConnection(first).first);
        Assertions.assertEquals(0, roles.getCurrentConnection(LIMITED_ROLE));
        Assertions.assertTrue(scheduler.registerRoleConnection(authenticate(first, Set.of(limitedRoleId))).first);
        Assertions.assertEquals(1, roles.getCurrentConnection(LIMITED_ROLE));

        ConnectContext second = newConnection("rcl_user2");
        Assertions.assertTrue(scheduler.registerConnection(second).first);
        Assertions.assertFalse(scheduler.registerRoleConnection(authenticate(second, Set.of(limitedRoleId))).first);
        // the rejected login closes its connection
        scheduler.unregisterConnection(second);
        Assertions.assertEquals(1, roles.getCurrentConnection(LIMITED_ROLE));

        scheduler.unregisterConnection(first);
        Assertions.assertEquals(0, roles.getCurrentConnection(LIMITED_ROLE));
    }

    @Test
    public void testConnectionClosedWhileAuthenticatingIsNotCounted() {
        ConnectScheduler scheduler = newScheduler();
        ConnectContext ctx = newConnection("rcl_user1");
        Assertions.assertTrue(scheduler.registerConnection(ctx).first);
        scheduler.unregisterConnection(ctx);

        Assertions.assertFalse(scheduler.registerRoleConnection(authenticate(ctx, Set.of(limitedRoleId))).first);
        Assertions.assertEquals(0, scheduler.getRoleConnectionManager().getCurrentConnection(LIMITED_ROLE));
    }

    // COM_CHANGE_USER, or an HTTP request as another user on a registered connection
    @Test
    public void testUserSwitchIsCountedAgainstTheNewRoles() {
        ConnectScheduler scheduler = newScheduler();
        RoleConnectionManager roles = scheduler.getRoleConnectionManager();
        ConnectContext first = authenticate(newConnection("rcl_user1"), Set.of(limitedRoleId));
        Assertions.assertTrue(scheduler.registerConnection(first).first);
        ConnectContext other = authenticate(newConnection("rcl_user2"), Set.of());
        Assertions.assertTrue(scheduler.registerConnection(other).first);

        // the context carries the new user and roles when the switch is checked
        other.setQualifiedUser("rcl_user1");
        authenticate(other, Set.of(limitedRoleId));
        Assertions.assertFalse(scheduler.onUserChanged(other, "rcl_user2", "rcl_user1").first);
        Assertions.assertEquals(1, scheduler.getUserConnectionMap().get("rcl_user1").get());
        Assertions.assertEquals(1, roles.getCurrentConnection(LIMITED_ROLE));

        // the caller restores the previous user, and the switch succeeds once the role has a free slot
        other.setQualifiedUser("rcl_user2");
        authenticate(other, Set.of());
        scheduler.unregisterConnection(first);
        other.setQualifiedUser("rcl_user1");
        authenticate(other, Set.of(limitedRoleId));
        Assertions.assertTrue(scheduler.onUserChanged(other, "rcl_user2", "rcl_user1").first);
        Assertions.assertEquals(1, roles.getCurrentConnection(LIMITED_ROLE));

        scheduler.unregisterConnection(other);
        Assertions.assertEquals(0, roles.getCurrentConnection(LIMITED_ROLE));
    }

    // roles granted to the user's external groups are active in the session, so they are counted too
    @Test
    public void testRoleOfExternalGroupIsCounted() {
        ConnectScheduler scheduler = newScheduler();
        ConnectContext first = newConnection("rcl_user1");
        first.setCurrentUserIdentity(new UserIdentity("rcl_user1", "%"));
        first.setCurrentRoleIds(first.getCurrentUserIdentity(), Set.of(GROUP));
        Assertions.assertTrue(scheduler.registerConnection(first).first);
        Assertions.assertEquals(1, scheduler.getRoleConnectionManager().getCurrentConnection(GROUP_ROLE));

        ConnectContext second = newConnection("rcl_user2");
        second.setCurrentUserIdentity(new UserIdentity("rcl_user2", "%"));
        second.setCurrentRoleIds(second.getCurrentUserIdentity(), Set.of(GROUP));
        Pair<Boolean, String> result = scheduler.registerConnection(second);
        Assertions.assertFalse(result.first);
        Assertions.assertTrue(result.second.contains("role=" + GROUP_ROLE), result.second);
    }
}
