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

package com.starrocks.http;

import com.starrocks.authentication.AuthenticationMgr;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.AuthorizationMgr;
import com.starrocks.authorization.DefaultAuthorizationProvider;
import com.starrocks.authorization.MockedLocalMetaStore;
import com.starrocks.authorization.RBACMockedMetadataMgr;
import com.starrocks.http.rest.RestBaseAction;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.DDLStmtExecutor;
import com.starrocks.qe.GlobalVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.CreateUserStmt;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Regression coverage for BaseAction#checkUserOwnsAdminRole not expanding nested role grants,
// e.g. a role granted db_admin/user_admin and then granted to a user (rather than db_admin/user_admin
// being granted to the user directly).
public class BaseActionAdminRoleTest {

    private ConnectContext ctx;

    static class TestableRestBaseAction extends RestBaseAction {
        TestableRestBaseAction() {
            super(null);
        }

        void assertOwnsAdminRole(ConnectContext connectContext) throws AccessDeniedException {
            checkUserOwnsAdminRole(connectContext);
        }

        void assertOwnsAdminRole(UserIdentity currentUser) throws AccessDeniedException {
            checkUserOwnsAdminRole(currentUser);
        }
    }

    private void setCurrentUserAndRoles(ConnectContext ctx, UserIdentity userIdentity) {
        ctx.setCurrentUserIdentity(userIdentity);
        ctx.setCurrentRoleIds(userIdentity);
    }

    @BeforeEach
    public void setUp() throws Exception {
        ctx = UtFrameUtils.initCtxForNewPrivilege(UserIdentity.ROOT);
        UtFrameUtils.setUpForPersistTest();

        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        MockedLocalMetaStore localMetastore = new MockedLocalMetaStore(globalStateMgr, globalStateMgr.getRecycleBin(), null);
        localMetastore.init();
        globalStateMgr.setLocalMetastore(localMetastore);
        globalStateMgr.setMetadataMgr(new RBACMockedMetadataMgr(localMetastore, globalStateMgr.getConnectorMgr()));

        globalStateMgr.setAuthenticationMgr(new AuthenticationMgr());
        globalStateMgr.setAuthorizationMgr(new AuthorizationMgr(new DefaultAuthorizationProvider()));

        GlobalVariable.setActivateAllRolesOnLogin(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        UtFrameUtils.tearDownForPersisTest();
    }

    private UserIdentity createUser(String name) throws Exception {
        CreateUserStmt createUserStmt = (CreateUserStmt) UtFrameUtils.parseStmtWithNewParser(
                "create user " + name, ctx);
        ctx.getGlobalStateMgr().getAuthenticationMgr().createUser(createUserStmt);
        return createUserStmt.getUserIdentity();
    }

    @Test
    public void testNestedRoleGrantingDbAdminAndUserAdminIsHonored() throws Exception {
        TestableRestBaseAction action = new TestableRestBaseAction();
        UserIdentity nestedAdminUser = createUser("nested_admin_user");

        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "create role my_admins;", ctx), ctx);
        // db_admin/user_admin are granted to the intermediate role, not to the user directly
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "grant db_admin to role my_admins;", ctx), ctx);
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "grant user_admin to role my_admins;", ctx), ctx);
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "grant my_admins to nested_admin_user;", ctx), ctx);

        setCurrentUserAndRoles(ctx, nestedAdminUser);
        // the session only carries the directly-granted my_admins role id; checkUserOwnsAdminRole
        // must expand it to find the inherited db_admin/user_admin roles
        Assertions.assertDoesNotThrow(() -> action.assertOwnsAdminRole(ctx));
        Assertions.assertDoesNotThrow(() -> action.assertOwnsAdminRole(ctx.getCurrentUserIdentity()));

        setCurrentUserAndRoles(ctx, UserIdentity.ROOT);
    }

    @Test
    public void testRoleMissingOneOfDbAdminOrUserAdminIsDenied() throws Exception {
        TestableRestBaseAction action = new TestableRestBaseAction();
        UserIdentity partialAdminUser = createUser("partial_admin_user");

        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "create role db_admin_only;", ctx), ctx);
        // only db_admin is nested in, user_admin is missing
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "grant db_admin to role db_admin_only;", ctx), ctx);
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "grant db_admin_only to partial_admin_user;", ctx), ctx);

        setCurrentUserAndRoles(ctx, partialAdminUser);
        Assertions.assertThrows(AccessDeniedException.class, () -> action.assertOwnsAdminRole(ctx));

        setCurrentUserAndRoles(ctx, UserIdentity.ROOT);
    }

    @Test
    public void testUnrelatedRoleIsDenied() throws Exception {
        TestableRestBaseAction action = new TestableRestBaseAction();
        UserIdentity plainUser = createUser("plain_user");

        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "create role plain_role;", ctx), ctx);
        DDLStmtExecutor.execute(UtFrameUtils.parseStmtWithNewParser(
                "grant plain_role to plain_user;", ctx), ctx);

        setCurrentUserAndRoles(ctx, plainUser);
        Assertions.assertThrows(AccessDeniedException.class, () -> action.assertOwnsAdminRole(ctx));

        setCurrentUserAndRoles(ctx, UserIdentity.ROOT);
    }
}
