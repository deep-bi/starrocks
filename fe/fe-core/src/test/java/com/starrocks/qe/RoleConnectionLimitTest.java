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

import com.starrocks.common.Config;
import com.starrocks.common.Pair;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.service.ExecuteEnv;
import com.starrocks.service.FrontendServiceImpl;
import com.starrocks.service.arrow.flight.sql.session.ArrowFlightSqlSessionManager;
import com.starrocks.sql.ast.CreateUserStmt;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.thrift.TLoadTxnBeginRequest;
import com.starrocks.thrift.TLoadTxnBeginResult;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.apache.arrow.flight.FlightRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Role-based connection limits as ConnectScheduler applies them to the connections it registers.
 */
public class RoleConnectionLimitTest {
    private static final String LIMITED_ROLE = "rcl_limited";
    // an external group maps onto the role of the same name
    private static final String GROUP = "rcl_group_analysts";
    private static final String ARROW_USER = "rcl_arrow";
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
        for (String role : List.of(LIMITED_ROLE, GROUP)) {
            DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser("CREATE ROLE " + role, rootCtx), rootCtx);
        }
        limitedRoleId = GlobalStateMgr.getCurrentState().getAuthorizationMgr().getRoleIdByNameAllowNull(LIMITED_ROLE);

        // a user that logs in with the limited role and may stream load into rcl_db.rcl_tbl
        new StarRocksAssert(rootCtx).withDatabase("rcl_db").useDatabase("rcl_db")
                .withTable("CREATE TABLE rcl_tbl (k1 INT) DUPLICATE KEY(k1) DISTRIBUTED BY HASH(k1) BUCKETS 1 "
                        + "PROPERTIES ('replication_num' = '1')");
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "GRANT INSERT ON TABLE rcl_db.rcl_tbl TO ROLE " + LIMITED_ROLE, rootCtx), rootCtx);
        CreateUserStmt createLoaderStmt = (CreateUserStmt) UtFrameUtils.parseStmtWithNewParser(
                "CREATE USER 'rcl_loader' IDENTIFIED BY '' DEFAULT ROLE " + LIMITED_ROLE, rootCtx);
        GlobalStateMgr.getCurrentState().getAuthenticationMgr().createUser(createLoaderStmt);

        // a user that logs in with the limited role, for Arrow Flight sessions
        CreateUserStmt createArrowUserStmt = (CreateUserStmt) UtFrameUtils.parseStmtWithNewParser(
                "CREATE USER '" + ARROW_USER + "' IDENTIFIED BY '' DEFAULT ROLE " + LIMITED_ROLE, rootCtx);
        GlobalStateMgr.getCurrentState().getAuthenticationMgr().createUser(createArrowUserStmt);
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
        Assertions.assertTrue(result.second.contains("Role-based connection limit exceeded"), result.second);
        Assertions.assertNull(scheduler.getContext(second.getConnectionId()));
        Assertions.assertEquals(1, scheduler.getConnectionNum());
        AtomicInteger secondUserConns = scheduler.getUserConnectionMap().get("rcl_user2");
        Assertions.assertTrue(secondUserConns == null || secondUserConns.get() == 0);

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

    // an HTTP SQL connection can close while a request on it authenticates as another user
    @Test
    public void testUserSwitchOfClosedConnectionIsNotCounted() {
        ConnectScheduler scheduler = newScheduler();
        ConnectContext ctx = authenticate(newConnection("rcl_user2"), Set.of());
        Assertions.assertTrue(scheduler.registerConnection(ctx).first);
        scheduler.unregisterConnection(ctx);

        ctx.setQualifiedUser("rcl_user1");
        authenticate(ctx, Set.of(limitedRoleId));
        Assertions.assertFalse(scheduler.onUserChanged(ctx, "rcl_user2", "rcl_user1").first);
        Assertions.assertEquals(0, scheduler.getRoleConnectionManager().getCurrentConnection(LIMITED_ROLE));
        Assertions.assertNull(scheduler.getUserConnectionMap().get("rcl_user1"));
    }

    // the role an external group maps onto is active in the session, so it is counted too
    @Test
    public void testRoleOfExternalGroupIsCounted() {
        ConnectScheduler scheduler = newScheduler();
        ConnectContext first = newConnection("rcl_user1");
        first.setCurrentUserIdentity(new UserIdentity("rcl_user1", "%"));
        first.setGroups(Set.of(GROUP));
        first.setCurrentRoleIds(first.getCurrentUserIdentity());
        Assertions.assertTrue(scheduler.registerConnection(first).first);
        Assertions.assertEquals(1, scheduler.getRoleConnectionManager().getCurrentConnection(GROUP));

        ConnectContext second = newConnection("rcl_user2");
        second.setCurrentUserIdentity(new UserIdentity("rcl_user2", "%"));
        second.setGroups(Set.of(GROUP));
        second.setCurrentRoleIds(second.getCurrentUserIdentity());
        Pair<Boolean, String> result = scheduler.registerConnection(second);
        Assertions.assertFalse(result.first);
        Assertions.assertTrue(result.second.contains("role=[" + GROUP + "]"), result.second);
    }

    // the BE checks the password of every stream load on a context that is never registered, so it takes no slot
    @Test
    public void testStreamLoadPasswordCheckIsNotCounted() throws Exception {
        ConnectScheduler scheduler = newScheduler();
        new MockUp<ExecuteEnv>() {
            @Mock
            public ConnectScheduler getScheduler() {
                return scheduler;
            }
        };

        FrontendServiceImpl service = new FrontendServiceImpl(ExecuteEnv.getInstance());
        for (int i = 0; i < 3; i++) {
            TLoadTxnBeginRequest request = new TLoadTxnBeginRequest();
            request.setUser("rcl_loader");
            request.setPasswd("");
            request.setUser_ip("127.0.0.1");
            request.setDb("rcl_db");
            request.setTbl("rcl_tbl");
            request.setLabel("rcl_stream_load_" + i);
            TLoadTxnBeginResult result = service.loadTxnBegin(request);
            Assertions.assertEquals(TStatusCode.OK, result.getStatus().getStatus_code(), result.getStatus().toString());
        }
        Assertions.assertEquals(0, scheduler.getRoleConnectionManager().getCurrentConnection(LIMITED_ROLE));

        // the role still has its slot for a connection
        ConnectContext ctx = authenticate(newConnection("rcl_loader"), Set.of(limitedRoleId));
        Assertions.assertTrue(scheduler.registerConnection(ctx).first);
        Assertions.assertEquals(1, scheduler.getRoleConnectionManager().getCurrentConnection(LIMITED_ROLE));
    }

    // the token cache drops an expired token, and so its session and role slot, only when it is maintained
    @Test
    public void testExpiredArrowFlightSessionReleasesItsSlot() throws Exception {
        ConnectScheduler scheduler = newScheduler();
        new MockUp<ExecuteEnv>() {
            @Mock
            public ConnectScheduler getScheduler() {
                return scheduler;
            }
        };
        int expireSecond = Config.arrow_token_cache_expire_second;
        Config.arrow_token_cache_expire_second = 1;
        try {
            ArrowFlightSqlSessionManager sessions = new ArrowFlightSqlSessionManager();
            Assertions.assertNotNull(sessions.initializeSession(ARROW_USER, "127.0.0.1", ""));
            Assertions.assertThrows(FlightRuntimeException.class,
                    () -> sessions.initializeSession(ARROW_USER, "127.0.0.1", ""));

            // once the first token expired, a login releases its session before the limits are checked
            Thread.sleep(1100);
            Assertions.assertNotNull(sessions.initializeSession(ARROW_USER, "127.0.0.1", ""));
            Assertions.assertEquals(1, scheduler.getRoleConnectionManager().getCurrentConnection(LIMITED_ROLE));
        } finally {
            Config.arrow_token_cache_expire_second = expireSecond;
        }
    }
}
