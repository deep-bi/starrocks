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

package bi.deep.starrocks.extension.warehouse;

import com.starrocks.alter.AlterMVJobExecutor;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.util.DynamicPartitionUtil;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.ModifyTablePropertyOperationLog;
import com.starrocks.persist.WALApplier;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.sql.ast.AlterMaterializedViewStmt;
import com.starrocks.sql.ast.ModifyTablePropertiesClause;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.sql.parser.AstBuilder;
import com.starrocks.sql.parser.SqlParser;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

/**
 * ALTER MATERIALIZED VIEW ... SET ('warehouse' = ...) moves the refreshes of a view to the warehouse it names. A
 * stock FE checks USAGE on that property for CREATE MATERIALIZED VIEW, but for the ALTER it checks only ALTER on
 * the view. The ALTER looks the warehouse up by name before it changes anything, and the extension checks the user
 * who runs the ALTER in that lookup.
 */
public class AlterMaterializedViewWarehouseTest {

    private static final String SET_RESEARCH = "ALTER MATERIALIZED VIEW poc.mv SET ('warehouse' = 'research')";

    private String savedRunMode;
    private FakeFeEnvironment fe;
    private MultiWarehouseManager manager;
    private long research;

    @BeforeEach
    public void before() throws Exception {
        savedRunMode = Config.run_mode;
        Config.run_mode = "shared_data";
        RunMode.detectRunMode();
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false));

        fe = new FakeFeEnvironment();
        manager = new MultiWarehouseManager(fe);
        manager.initDefaultWarehouse();
        manager.createWarehouse(new CreateWarehouseStmt(false, "research", null, null));
        research = manager.getWarehouse("research").getId();
    }

    @AfterEach
    public void after() {
        Config.run_mode = savedRunMode;
        RunMode.detectRunMode();
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    // ---------------------------------------------------------------- the check, with the fake environment

    @Test
    public void alterMaterializedViewNeedsUsageOnTheWarehouseItNames() {
        fe.sessionUser = "analyst";
        fe.settingMaterializedViewProperties = true;

        FakeFeEnvironment.AccessDenied denied = Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> manager.getWarehouse("research"));
        Assertions.assertTrue(denied.getMessage().contains("analyst needs USAGE on WAREHOUSE research"), denied.getMessage());

        fe.usageGrants.add("analyst on research");
        Assertions.assertEquals(research, manager.getWarehouse("research").getId());
        Assertions.assertEquals(Arrays.asList("analyst on research", "analyst on research"), fe.usageChecks);
    }

    /**
     * The FE looks warehouses up by name for many other statements and for loads. Those lookups are not checked:
     * the gate checks USAGE where compute is acquired.
     */
    @Test
    public void aLookupByNameForAnyOtherStatementIsNotChecked() {
        fe.sessionUser = "analyst";

        Assertions.assertEquals(research, manager.getWarehouse("research").getId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void movingAViewToTheDefaultWarehouseIsNotChecked() {
        fe.sessionUser = "analyst";
        fe.settingMaterializedViewProperties = true;

        Assertions.assertEquals(WarehouseManager.DEFAULT_WAREHOUSE_ID,
                manager.getWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_NAME).getId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void theSwitchAlsoTurnsOffTheCheckOfAlterMaterializedView() {
        fe.sessionUser = "analyst";
        fe.settingMaterializedViewProperties = true;
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false, false));

        Assertions.assertEquals(research, manager.getWarehouse("research").getId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    /** The journal replay of the ALTER looks the warehouse up too, on a thread without a user session. */
    @Test
    public void aLookupWithoutAUserSessionIsNotChecked() {
        fe.sessionUser = null;
        fe.settingMaterializedViewProperties = true;

        Assertions.assertEquals(research, manager.getWarehouse("research").getId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    /** A name the FE does not know gets the FE's own error, not a privilege error. */
    @Test
    public void anUnknownWarehouseIsReportedAsMissingNotAsDenied() {
        fe.sessionUser = "analyst";
        fe.settingMaterializedViewProperties = true;

        ErrorReportException missing = Assertions.assertThrows(ErrorReportException.class,
                () -> manager.getWarehouse("nope"));

        Assertions.assertEquals(ErrorCode.ERR_UNKNOWN_WAREHOUSE, missing.getErrorCode());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    // ---------------------------------------------------------------- which statement, with the live environment

    /** The live environment finds the statement on the session's executor, where the FE puts it before running it. */
    @Test
    public void theLiveEnvironmentChecksAlterMaterializedViewSet(@Mocked GlobalStateMgr state,
                                                                 @Mocked Authorizer authorizer) throws Exception {
        ConnectContext session = analystSession(state);
        run(session, SET_RESEARCH);
        new Expectations() {
            {
                Authorizer.checkWarehouseAction(session, "research", PrivilegeType.USAGE);
                result = new AccessDeniedException();
            }
        };

        try (var ignored = session.bindScope()) {
            ErrorReportException denied = Assertions.assertThrows(ErrorReportException.class, () -> lookUp("research"));
            Assertions.assertEquals(ErrorCode.ERR_ACCESS_DENIED, denied.getErrorCode());
            Assertions.assertTrue(denied.getMessage().contains("the USAGE privilege(s) on WAREHOUSE research"),
                    denied.getMessage());
        }
    }

    /**
     * Every other statement of the session gets the warehouse unchecked: the other clauses of ALTER MATERIALIZED
     * VIEW, ALTER TABLE ... SET, and CREATE MATERIALIZED VIEW, whose warehouse property the stock FE checks itself.
     */
    @Test
    public void theLiveEnvironmentLeavesOtherStatementsUnchecked(@Mocked GlobalStateMgr state,
                                                                 @Mocked Authorizer authorizer) throws Exception {
        ConnectContext session = analystSession(state);
        new Expectations() {
            {
                Authorizer.checkWarehouseAction((ConnectContext) any, anyString, (PrivilegeType) any);
                result = new AccessDeniedException();
                minTimes = 0;
            }
        };
        List<String> statements = Arrays.asList(
                "ALTER MATERIALIZED VIEW poc.mv REFRESH ASYNC EVERY (INTERVAL 1 DAY)",
                "ALTER MATERIALIZED VIEW poc.mv ACTIVE",
                "ALTER TABLE poc.t SET ('replication_num' = '1')",
                "CREATE MATERIALIZED VIEW poc.mv REFRESH MANUAL PROPERTIES ('warehouse' = 'research') AS SELECT 1",
                "INSERT INTO poc.t SELECT 1");

        try (var ignored = session.bindScope()) {
            long found = Assertions.assertDoesNotThrow(() -> lookUp("research"), "a session without a statement");
            Assertions.assertEquals(research, found);
        }
        for (String sql : statements) {
            run(session, sql);
            try (var ignored = session.bindScope()) {
                long found = Assertions.assertDoesNotThrow(() -> lookUp("research"), sql);
                Assertions.assertEquals(research, found, sql);
            }
        }
        new Verifications() {
            {
                Authorizer.checkWarehouseAction((ConnectContext) any, anyString, (PrivilegeType) any);
                times = 0;
            }
        };
    }

    // ---------------------------------------------------------------- the FE's own ALTER MATERIALIZED VIEW code
    // DynamicPartitionUtil is mocked: after the edit log the FE registers the view with its partition TTL
    // scheduler, and that scheduler is a Thread, which JMockit cannot mock.

    /**
     * The FE's own code for ALTER MATERIALIZED VIEW ... SET ('warehouse' = 'research'), run by a user without
     * USAGE. It looks the warehouse up while it collects the changes, before it writes the edit log, so the user is
     * refused and nothing changes. The lookup also comes after the statement's own map has lost the warehouse key,
     * so the check cannot look for that key. This test fails when a later FE looks the warehouse up some other way.
     */
    @Test
    public void alterMaterializedViewIsRefusedBeforeItChangesAnything(
            @Mocked GlobalStateMgr state, @Mocked Authorizer authorizer, @Mocked MaterializedView view,
            @Mocked EditLog editLog, @Mocked DynamicPartitionUtil partitionTtl) throws Exception {
        ConnectContext session = analystSession(state);
        AlterMaterializedViewStmt alter = (AlterMaterializedViewStmt) run(session, SET_RESEARCH);
        new Expectations() {
            {
                state.getEditLog();
                result = editLog;
                minTimes = 0;
                Authorizer.checkWarehouseAction(session, "research", PrivilegeType.USAGE);
                result = new AccessDeniedException();
            }
        };

        try (var ignored = session.bindScope()) {
            ErrorReportException denied = Assertions.assertThrows(ErrorReportException.class,
                    () -> new ViewAlter(view).visitModifyTablePropertiesClause(
                            (ModifyTablePropertiesClause) alter.getAlterTableClause(), session));
            Assertions.assertEquals(ErrorCode.ERR_ACCESS_DENIED, denied.getErrorCode());
        }
        new Verifications() {
            {
                editLog.logAlterMaterializedViewProperties((ModifyTablePropertyOperationLog) any, (WALApplier) any);
                times = 0;
                view.setWarehouseId(anyLong);
                times = 0;
            }
        };
    }

    /** With USAGE, the same statement writes the edit log and moves the refreshes of the view to research. */
    @Test
    public void withUsageAlterMaterializedViewMovesTheView(
            @Mocked GlobalStateMgr state, @Mocked Authorizer authorizer, @Mocked MaterializedView view,
            @Mocked EditLog editLog, @Mocked DynamicPartitionUtil partitionTtl) throws Exception {
        ConnectContext session = analystSession(state);
        AlterMaterializedViewStmt alter = (AlterMaterializedViewStmt) run(session, SET_RESEARCH);
        new Expectations() {
            {
                state.getEditLog();
                result = editLog;
                editLog.logAlterMaterializedViewProperties((ModifyTablePropertyOperationLog) any, (WALApplier) any);
                result = new Delegate<Void>() {
                    @SuppressWarnings("unused")
                    void write(ModifyTablePropertyOperationLog log, WALApplier afterWrite) {
                        afterWrite.apply(log);
                    }
                };
            }
        };

        try (var ignored = session.bindScope()) {
            new ViewAlter(view).visitModifyTablePropertiesClause(
                    (ModifyTablePropertiesClause) alter.getAlterTableClause(), session);
        }
        new Verifications() {
            {
                view.setWarehouseId(research);
                times = 1;
                Authorizer.checkWarehouseAction(session, "research", PrivilegeType.USAGE);
                times = 1;
            }
        };
    }

    /** The FE's ALTER MATERIALIZED VIEW code, holding the view the FE would have looked up and locked. */
    private static final class ViewAlter extends AlterMVJobExecutor {
        ViewAlter(MaterializedView view) {
            table = view;
        }
    }

    /**
     * A session of analyst, as the FE sets one up for a connection. Its warehouse manager is a replica of this
     * test's, reached through the live environment.
     */
    private ConnectContext analystSession(GlobalStateMgr state) {
        MultiWarehouseManager sessionManager = new MultiWarehouseManager(FeEnvironment.live());
        fe.replayInto(sessionManager);
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getVariableMgr().newSessionVariable();
                result = new SessionVariable();
                state.getSqlParser();
                result = new SqlParser(AstBuilder.getInstance());
                minTimes = 0;
                state.getWarehouseMgr();
                result = sessionManager;
                minTimes = 0;
            }
        };

        ConnectContext session = new ConnectContext();
        session.setCurrentUserIdentity(new UserIdentity("analyst", "%"));
        return session;
    }

    /** Puts the statement on the session's executor, as the FE does before it runs a statement. */
    private static StatementBase run(ConnectContext session, String sql) {
        StatementBase statement = SqlParser.parse(sql, new SessionVariable()).get(0);
        session.setExecutor(new StmtExecutor(session, statement));
        return statement;
    }

    /** Looks a warehouse up by name the way the FE's code does. */
    private static long lookUp(String name) {
        return GlobalStateMgr.getCurrentState().getWarehouseMgr().getWarehouse(name).getId();
    }
}
