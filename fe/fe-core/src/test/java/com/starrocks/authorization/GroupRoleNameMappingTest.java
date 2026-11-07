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

package com.starrocks.authorization;

import com.starrocks.authentication.AuthenticationMgr;
import com.starrocks.common.Config;
import com.starrocks.common.StarRocksException;
import com.starrocks.persist.EditLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.DDLStmtExecutor;
import com.starrocks.qe.SetRoleExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.sql.ast.CreateTableStmt;
import com.starrocks.sql.ast.GrantPrivilegeStmt;
import com.starrocks.sql.ast.GrantRoleStmt;
import com.starrocks.sql.ast.SetRoleStmt;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.transaction.MockedLocalMetaStore;
import com.starrocks.transaction.MockedMetadataMgr;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

// authorization_map_groups_to_roles_by_name: a group also holds the role of the same name, on top of the roles
// granted to it with GRANT ... TO EXTERNAL GROUP, and nothing changes while the option is off.
public class GroupRoleNameMappingTest {
    private static final Set<String> ANALYSTS = Set.of("analysts");

    private ConnectContext ctx;
    private AuthorizationMgr authorizationMgr;
    private boolean privCollectionCache;

    @BeforeEach
    public void setUp() throws Exception {
        privCollectionCache = Config.authorization_enable_priv_collection_cache;
        Config.authorization_enable_priv_collection_cache = true;

        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        EditLog editLog = spy(new EditLog(null));
        doNothing().when(editLog).logEdit(anyShort(), any());
        globalStateMgr.setEditLog(editLog);

        ctx = new ConnectContext();
        ctx.setThreadLocalInfo();
        ctx.setGlobalStateMgr(globalStateMgr);

        MockedLocalMetaStore localMetastore =
                new MockedLocalMetaStore(globalStateMgr, globalStateMgr.getRecycleBin(), null);
        globalStateMgr.setLocalMetastore(localMetastore);
        globalStateMgr.setMetadataMgr(new MockedMetadataMgr(localMetastore, globalStateMgr.getConnectorMgr()));
        localMetastore.createDb("db1");
        CreateTableStmt createTableStmt = (CreateTableStmt) SqlParser.parseSingleStatement(
                "create table db1.tbl1 (c1 bigint, c2 bigint, c3 bigint)", ctx.getSessionVariable().getSqlMode());
        Analyzer.analyze(createTableStmt, ctx);
        localMetastore.createTable(createTableStmt);

        authorizationMgr = new AuthorizationMgr(new DefaultAuthorizationProvider());
        globalStateMgr.setAuthorizationMgr(authorizationMgr);
        globalStateMgr.setAuthenticationMgr(new AuthenticationMgr());

        execute("create user u1");
    }

    @AfterEach
    public void tearDown() {
        Config.authorization_map_groups_to_roles_by_name = false;
        Config.authorization_enable_priv_collection_cache = privCollectionCache;
    }

    private void execute(String sql) throws Exception {
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(sql, ctx), ctx);
    }

    private void grant(String sql) throws Exception {
        authorizationMgr.grant((GrantPrivilegeStmt) UtFrameUtils.parseStmtWithNewParser(sql, ctx));
    }

    private void grantRoleToGroup(String role, String group) throws Exception {
        GrantRoleStmt grantRoleStmt = new GrantRoleStmt(List.of(role), group, GrantType.GROUP, NodePosition.ZERO);
        Analyzer.analyze(grantRoleStmt, ctx);
        authorizationMgr.grantRole(grantRoleStmt);
    }

    private void loginAs(UserIdentity user, Set<String> groups) {
        ctx.setCurrentUserIdentity(user);
        ctx.setGroups(groups);
        ctx.setCurrentRoleIds(user, groups);
    }

    private boolean can(PrivilegeType privilegeType) {
        try {
            Authorizer.checkTableAction(ctx, "db1", "tbl1", privilegeType);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    @Test
    public void testOffByDefault() throws Exception {
        Assertions.assertFalse(Config.authorization_map_groups_to_roles_by_name);
        execute("create role analysts");
        grant("grant select on table db1.tbl1 to role analysts");

        loginAs(new UserIdentity("u1", "%"), ANALYSTS);
        Assertions.assertTrue(authorizationMgr.getRoleIdsMappedByGroupName(ANALYSTS).isEmpty());
        Assertions.assertFalse(ctx.getCurrentRoleIds().contains(authorizationMgr.getRoleIdByNameAllowNull("analysts")));
        Assertions.assertFalse(can(PrivilegeType.SELECT));
    }

    @Test
    public void testGroupHoldsRoleOfSameName() throws Exception {
        Config.authorization_map_groups_to_roles_by_name = true;
        execute("create role analysts");
        grant("grant select on table db1.tbl1 to role analysts");
        Long analystsId = authorizationMgr.getRoleIdByNameAllowNull("analysts");

        loginAs(new UserIdentity("u1", "%"), ANALYSTS);
        Assertions.assertEquals(Set.of(analystsId), authorizationMgr.getRoleIdsMappedByGroupName(ANALYSTS));
        Assertions.assertTrue(ctx.getCurrentRoleIds().contains(analystsId));
        Assertions.assertTrue(can(PrivilegeType.SELECT));

        loginAs(new UserIdentity("u1", "%"), Set.of("analysts_eu"));
        Assertions.assertFalse(can(PrivilegeType.SELECT));
    }

    @Test
    public void testAddsToRolesGrantedToGroup() throws Exception {
        execute("create role r1");
        grant("grant select on table db1.tbl1 to role r1");
        grantRoleToGroup("r1", "analysts");
        execute("create role analysts");
        grant("grant insert on table db1.tbl1 to role analysts");

        loginAs(new UserIdentity("u1", "%"), ANALYSTS);
        Assertions.assertTrue(can(PrivilegeType.SELECT));
        Assertions.assertFalse(can(PrivilegeType.INSERT));

        // the option is not mutable, so flipping it here needs a fresh cache and a fresh login
        Config.authorization_map_groups_to_roles_by_name = true;
        authorizationMgr.ctxToMergedPrivilegeCollections.invalidateAll();
        loginAs(new UserIdentity("u1", "%"), ANALYSTS);
        Assertions.assertTrue(can(PrivilegeType.SELECT));
        Assertions.assertTrue(can(PrivilegeType.INSERT));
        Assertions.assertEquals(Set.of(authorizationMgr.getRoleIdByNameAllowNull("r1")),
                authorizationMgr.getRoleIdListByGroup("analysts"));
    }

    @Test
    public void testBuiltInRolesAreNotMapped() {
        Config.authorization_map_groups_to_roles_by_name = true;
        Set<String> builtInNames = PrivilegeBuiltinConstants.BUILT_IN_ROLE_NAMES;
        Assertions.assertTrue(builtInNames.contains("root"));
        Assertions.assertTrue(builtInNames.contains("db_admin"));

        loginAs(new UserIdentity("u1", "%"), builtInNames);
        Assertions.assertTrue(authorizationMgr.getRoleIdsMappedByGroupName(builtInNames).isEmpty());
        Assertions.assertFalse(ctx.getCurrentRoleIds().contains(PrivilegeBuiltinConstants.ROOT_ROLE_ID));
        Assertions.assertFalse(can(PrivilegeType.SELECT));
    }

    @Test
    public void testEphemeralUser() throws Exception {
        Config.authorization_map_groups_to_roles_by_name = true;
        execute("create role analysts");
        grant("grant select on table db1.tbl1 to role analysts");
        Long analystsId = authorizationMgr.getRoleIdByNameAllowNull("analysts");
        UserIdentity ldapUser = UserIdentity.createEphemeralUserIdent("ldap_user", "%");

        loginAs(ldapUser, ANALYSTS);
        Assertions.assertEquals(Set.of(analystsId), ctx.getCurrentRoleIds());
        Assertions.assertTrue(can(PrivilegeType.SELECT));

        // a role that is not among the session's role ids still applies through the group, and resolving it
        // leaves the session's own set, which is also the cache key, untouched
        Set<Long> sessionRoleIds = new HashSet<>();
        ctx.setCurrentRoleIds(sessionRoleIds);
        Assertions.assertTrue(can(PrivilegeType.SELECT));
        Assertions.assertTrue(sessionRoleIds.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testCachedPrivilegesFollowTheMappedRole(boolean ephemeral) throws Exception {
        Config.authorization_map_groups_to_roles_by_name = true;
        UserIdentity user = ephemeral ? UserIdentity.createEphemeralUserIdent("ldap_user", "%") :
                new UserIdentity("u1", "%");
        loginAs(user, ANALYSTS);
        // Cache the denied check before the matching role exists. This session never holds its ID.
        Assertions.assertTrue(ctx.getCurrentRoleIds().isEmpty());
        Assertions.assertFalse(can(PrivilegeType.SELECT));

        execute("create role analysts");
        Assertions.assertFalse(can(PrivilegeType.SELECT));

        grant("grant select on table db1.tbl1 to role analysts");
        Assertions.assertTrue(can(PrivilegeType.SELECT));

        execute("revoke select on table db1.tbl1 from role analysts");
        Assertions.assertFalse(can(PrivilegeType.SELECT));

        grant("grant select on table db1.tbl1 to role analysts");
        Assertions.assertTrue(can(PrivilegeType.SELECT));

        execute("drop role analysts");
        Assertions.assertFalse(can(PrivilegeType.SELECT));
        Assertions.assertTrue(ctx.getCurrentRoleIds().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testCachedPrivilegesFollowInheritedRoles(boolean ephemeral) throws Exception {
        Config.authorization_map_groups_to_roles_by_name = true;
        UserIdentity user = ephemeral ? UserIdentity.createEphemeralUserIdent("ldap_user", "%") :
                new UserIdentity("u1", "%");
        loginAs(user, ANALYSTS);
        Assertions.assertFalse(can(PrivilegeType.SELECT));

        execute("create role analysts, reader");
        grant("grant select on table db1.tbl1 to role reader");
        Assertions.assertFalse(can(PrivilegeType.SELECT));

        execute("grant reader to role analysts");
        Assertions.assertTrue(can(PrivilegeType.SELECT));

        // Changes to an ancestor role must invalidate the mapped descendant's cached privileges.
        execute("revoke select on table db1.tbl1 from role reader");
        Assertions.assertFalse(can(PrivilegeType.SELECT));
        grant("grant select on table db1.tbl1 to role reader");
        Assertions.assertTrue(can(PrivilegeType.SELECT));

        execute("revoke reader from role analysts");
        Assertions.assertFalse(can(PrivilegeType.SELECT));
        Assertions.assertTrue(ctx.getCurrentRoleIds().isEmpty());
    }

    @Test
    public void testSetRoleNoneRetainsMappedPrivileges() throws Exception {
        Config.authorization_map_groups_to_roles_by_name = true;
        execute("create role analysts, writer");
        grant("grant select on table db1.tbl1 to role analysts");
        grant("grant insert on table db1.tbl1 to role writer");
        execute("grant writer to u1");
        loginAs(new UserIdentity("u1", "%"), ANALYSTS);

        SetRoleExecutor.execute((SetRoleStmt) UtFrameUtils.parseStmtWithNewParser("set role all", ctx), ctx);
        Assertions.assertTrue(can(PrivilegeType.SELECT));
        Assertions.assertTrue(can(PrivilegeType.INSERT));

        SetRoleExecutor.execute((SetRoleStmt) UtFrameUtils.parseStmtWithNewParser("set role none", ctx), ctx);
        Assertions.assertTrue(ctx.getCurrentRoleIds().isEmpty());
        Assertions.assertTrue(can(PrivilegeType.SELECT));
        Assertions.assertFalse(can(PrivilegeType.INSERT));

        StarRocksException exception = Assertions.assertThrows(StarRocksException.class,
                () -> SetRoleExecutor.execute((SetRoleStmt) UtFrameUtils.parseStmtWithNewParser(
                        "set role analysts", ctx), ctx));
        Assertions.assertTrue(exception.getMessage().contains("Role analysts is not granted"));
    }
}
