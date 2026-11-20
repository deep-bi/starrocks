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

import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.util.UUIDUtil;
import com.starrocks.lake.StarOSAgent;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.CRAcquireContext;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.ComputeResourceProvider;
import com.starrocks.warehouse.cngroup.LazyComputeResource;
import com.starrocks.warehouse.cngroup.WarehouseComputeResource;
import com.starrocks.warehouse.cngroup.WarehouseComputeResourceProvider;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mocked;
import mockit.Verifications;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

/**
 * Tests warehouse USAGE for user work and warehouse selection for background work.
 *
 * <p>The tests run the FE's own acquire code in shared-data mode. Only the last step, finding live nodes, is
 * replaced, by a provider that records which warehouse it was asked for.
 */
public class WarehouseGateTest {

    /** Records each request and supplies a compute resource when live nodes are available. */
    private static final class RecordingProvider implements ComputeResourceProvider {
        final List<Long> served = new ArrayList<>();

        @Override
        public Optional<ComputeResource> acquireComputeResource(Warehouse warehouse, CRAcquireContext acquireContext) {
            served.add(warehouse.getId());
            if (!hasLiveNodes) {
                return Optional.empty();
            }
            return Optional.of(WarehouseComputeResource.of(warehouse.getId()));
        }

        @Override
        public ComputeResource ofComputeResource(long warehouseId, long workGroupId) {
            served.add(warehouseId);
            return WarehouseComputeResource.of(warehouseId);
        }

        @Override
        public List<ComputeResource> getComputeResources(Warehouse warehouse) {
            served.add(warehouse.getId());
            return Collections.singletonList(WarehouseComputeResource.of(warehouse.getId()));
        }

        /** What the FE's own availability check answers: whether the warehouse has live nodes. */
        boolean hasLiveNodes = true;

        @Override
        public boolean isResourceAvailable(ComputeResource computeResource) {
            return hasLiveNodes;
        }

        @Override
        public List<Long> getAllComputeNodeIds(ComputeResource computeResource) {
            return Collections.emptyList();
        }

        @Override
        public List<ComputeNode> getAliveComputeNodes(ComputeResource computeResource) {
            return Collections.emptyList();
        }
    }

    private String savedRunMode;
    private String savedBackgroundWarehouse;
    private String savedCompactionWarehouse;
    private FakeFeEnvironment fe;
    private RecordingProvider provider;
    private MultiWarehouseManager manager;
    private long research;
    private long etl;

    @BeforeEach
    public void before() throws Exception {
        savedRunMode = Config.run_mode;
        savedBackgroundWarehouse = Config.lake_background_warehouse;
        savedCompactionWarehouse = Config.lake_compaction_warehouse;
        Config.run_mode = "shared_data";
        RunMode.detectRunMode();
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false));

        fe = new FakeFeEnvironment();
        provider = new RecordingProvider();
        manager = new MultiWarehouseManager(fe, provider);
        manager.initDefaultWarehouse();
        manager.createWarehouse(new CreateWarehouseStmt(false, "research", null, null));
        manager.createWarehouse(new CreateWarehouseStmt(false, "etl", null, null));
        research = manager.getWarehouse("research").getId();
        etl = manager.getWarehouse("etl").getId();
    }

    @AfterEach
    public void after() {
        Config.lake_background_warehouse = savedBackgroundWarehouse;
        Config.lake_compaction_warehouse = savedCompactionWarehouse;
        Config.run_mode = savedRunMode;
        RunMode.detectRunMode();
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    @Test
    public void aUserWithUsageGetsTheNodesOfTheWarehouse() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");

        ComputeResource resource = manager.acquireComputeResource(research);

        Assertions.assertEquals(research, resource.getWarehouseId());
        Assertions.assertEquals(Collections.singletonList("analyst on research"), fe.usageChecks);
        Assertions.assertEquals(Collections.singletonList(research), provider.served);
    }

    /**
     * Refused before the FE's own code runs, so no node of the warehouse is handed out.
     */
    @Test
    public void aUserWithoutUsageIsRefusedBeforeAnyNodeIsHandedOut() {
        fe.sessionUser = "analyst";

        FakeFeEnvironment.AccessDenied denied = Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> manager.acquireComputeResource(research));

        Assertions.assertTrue(denied.getMessage().contains("analyst needs USAGE on WAREHOUSE research"), denied.getMessage());
        Assertions.assertTrue(provider.served.isEmpty());
    }

    @Test
    public void aGrantOnOneWarehouseDoesNotOpenAnother() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");

        Assertions.assertDoesNotThrow(() -> manager.acquireComputeResource(research));
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.acquireComputeResource(etl));
        Assertions.assertEquals(Collections.singletonList(research), provider.served);
    }

    @Test
    public void cacheSelectRequiresUsageBeforeListingWarehouseResources() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResourceProvider cacheSelectProvider = manager.getComputeResourceProvider();

        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> cacheSelectProvider.getComputeResources(manager.getWarehouse("etl")));
        Assertions.assertEquals(Collections.singletonList("analyst on etl"), fe.usageChecks);
        Assertions.assertTrue(provider.served.isEmpty());

        fe.nextStatement();
        List<ComputeResource> resources = cacheSelectProvider.getComputeResources(manager.getWarehouse("research"));
        Assertions.assertEquals(1, resources.size());
        Assertions.assertEquals(research, resources.get(0).getWarehouseId());
        Assertions.assertEquals(Collections.singletonList(research), provider.served);
    }

    @Test
    public void cacheSelectPreservesTheFeErrorForAMissingWarehouse() {
        fe.sessionUser = "analyst";
        ComputeResourceProvider resources = new MultiWarehouseManager(fe).getComputeResourceProvider();

        ErrorReportException error = Assertions.assertThrows(ErrorReportException.class,
                () -> resources.getComputeResources(null));
        Assertions.assertEquals(ErrorCode.ERR_UNKNOWN_WAREHOUSE, error.getErrorCode());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void nodeLookupsUseTheUnderlyingProvider(@Injectable ComputeResourceProvider delegate) {
        ComputeResource resource = WarehouseComputeResource.of(research);
        long groupId = ((UserDefinedWarehouse) manager.getWarehouse(research)).getWorkerGroupId();
        ComputeNode node = new ComputeNode(20001L, "cn", 9050);
        new Expectations() {
            {
                delegate.ofComputeResource(research, groupId);
                result = resource;
                delegate.getAllComputeNodeIds(resource);
                result = Collections.singletonList(node.getId());
                delegate.getAliveComputeNodes(resource);
                result = Collections.singletonList(node);
            }
        };

        ComputeResourceProvider resources = new MultiWarehouseManager(fe, delegate).getComputeResourceProvider();
        Assertions.assertSame(resource, resources.ofComputeResource(research, groupId));
        Assertions.assertEquals(Collections.singletonList(node.getId()), resources.getAllComputeNodeIds(resource));
        Assertions.assertEquals(Collections.singletonList(node), resources.getAliveComputeNodes(resource));
    }

    /**
     * Every user may use the default warehouse, as in a stock FE. Its requests are not checked at all.
     */
    @Test
    public void theDefaultWarehouseIsNeverChecked() {
        fe.sessionUser = "analyst";
        long defaultId = WarehouseManager.DEFAULT_WAREHOUSE_ID;

        ComputeResource resource = manager.acquireComputeResource(defaultId);
        List<ComputeResource> listed = manager.getComputeResourceProvider()
                .getComputeResources(manager.getWarehouse(defaultId));

        Assertions.assertEquals(defaultId, resource.getWarehouseId());
        Assertions.assertEquals(defaultId, listed.get(0).getWarehouseId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
        Assertions.assertEquals(Arrays.asList(defaultId, defaultId), provider.served);
    }

    /**
     * Compaction and statistics run on FE threads with no user session. They must keep working on any
     * warehouse.
     */
    @Test
    public void backgroundWorkIsNotChecked() {
        fe.sessionUser = null;

        ComputeResource resource = manager.acquireComputeResource(research);

        Assertions.assertEquals(research, resource.getWarehouseId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void maintenanceOnAUserThreadUsesItsConfiguredWarehouses() {
        fe.sessionUser = "analyst";
        Config.lake_background_warehouse = "research";
        Config.lake_compaction_warehouse = "etl";

        Assertions.assertEquals(research, manager.getBackgroundComputeResource().getWarehouseId());
        Assertions.assertEquals(research, manager.getBackgroundComputeResource(42L).getWarehouseId());
        Assertions.assertEquals(etl, manager.getCompactionComputeResource(42L).getWarehouseId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());

        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.acquireComputeResource(research));
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.acquireComputeResource(etl));
        Assertions.assertEquals(Arrays.asList("analyst on research", "analyst on etl"), fe.usageChecks);
        Assertions.assertEquals(Arrays.asList(research, research, etl), provider.served);
    }

    @Test
    public void backgroundAndCompactionFollowTheirOwnLiveSettings() {
        Config.lake_background_warehouse = "research";
        Config.lake_compaction_warehouse = "etl";
        long tableId = 42L;

        Assertions.assertEquals(research, manager.getBackgroundWarehouse().getId());
        Assertions.assertEquals(research, manager.getBackgroundWarehouse(tableId).getId());
        Assertions.assertEquals(research, manager.getBackgroundComputeResource().getWarehouseId());
        Assertions.assertEquals(research, manager.getBackgroundComputeResource(tableId).getWarehouseId());
        Assertions.assertEquals(etl, manager.getCompactionComputeResource(tableId).getWarehouseId());

        Config.lake_background_warehouse = "etl";
        Config.lake_compaction_warehouse = "research";
        Assertions.assertEquals(etl, manager.getBackgroundComputeResource(tableId).getWarehouseId());
        Assertions.assertEquals(research, manager.getCompactionComputeResource(tableId).getWarehouseId());
        Assertions.assertEquals(Arrays.asList(research, research, etl, etl, research), provider.served);
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"default_warehouse", "DEFAULT_WAREHOUSE", "missing"})
    public void unsetDefaultAndUnknownWarehouseNamesUseTheFeDefaults(String name) {
        Config.lake_background_warehouse = name;
        Config.lake_compaction_warehouse = name;

        Assertions.assertEquals(WarehouseManager.DEFAULT_WAREHOUSE_ID, manager.getBackgroundWarehouse().getId());
        Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE, manager.getCompactionComputeResource(42L));
        Assertions.assertTrue(provider.served.isEmpty());
    }

    @Test
    public void unknownWarehouseWarningsAreLimitedSeparatelyForEachSetting() {
        Config.lake_background_warehouse = "missing";
        Config.lake_compaction_warehouse = "missing";
        List<LogEvent> warnings = captureRoutingWarnings(() -> {
            IntStream.range(0, 32).parallel().forEach(i -> manager.getBackgroundWarehouse(i));
            manager.getCompactionComputeResource(1L);
            fe.clock += 599_999L;
            manager.getBackgroundWarehouse();
            manager.getCompactionComputeResource(2L);
            Config.lake_compaction_warehouse = "another_missing";
            manager.getCompactionComputeResource(3L);
            fe.clock++;
            manager.getBackgroundWarehouse();
            // A clock correction must not silence warnings until the old wall time is reached.
            fe.clock -= 3_600_000L;
            manager.getBackgroundWarehouse();
        });

        Assertions.assertEquals(5, warnings.size());
        String firstBackground = warnings.get(0).getMessage().getFormattedMessage();
        Assertions.assertTrue(firstBackground.contains("lake_background_warehouse"));
        Assertions.assertTrue(warnings.get(1).getMessage().getFormattedMessage().contains("lake_compaction_warehouse"));
        Assertions.assertTrue(warnings.get(2).getMessage().getFormattedMessage().contains("another_missing"));
        Assertions.assertEquals(firstBackground, warnings.get(3).getMessage().getFormattedMessage());
        Assertions.assertEquals(firstBackground, warnings.get(4).getMessage().getFormattedMessage());
    }

    @Test
    public void emptyCompactionWarehouseRetriesEveryPartitionAndOnlyLogsAtIntervals(
            @Mocked GlobalStateMgr state, @Injectable StarOSAgent starMgr) throws Exception {
        MultiWarehouseManager stockProviderManager = new MultiWarehouseManager(fe);
        Warehouse warehouse = manager.getWarehouse(research);
        stockProviderManager.replayCreateWarehouse(warehouse);
        long groupId = warehouse.getAnyWorkerGroupId();
        Config.lake_compaction_warehouse = warehouse.getName();
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getWarehouseMgr();
                result = stockProviderManager;
                state.getStarOSAgent();
                result = starMgr;
                starMgr.getWorkersByWorkerGroup(groupId);
                result = Collections.emptyList();
                times = 4;
            }
        };

        List<LogEvent> warnings = captureRoutingWarnings(() -> {
            for (long tableId = 1; tableId <= 3; tableId++) {
                Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE,
                        stockProviderManager.getCompactionComputeResource(tableId));
            }
            fe.clock += 600_000L;
            Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE, stockProviderManager.getCompactionComputeResource(4L));
        });
        Assertions.assertEquals(2, warnings.size());
        for (LogEvent warning : warnings) {
            Assertions.assertEquals(MultiWarehouseManager.class.getName(), warning.getLoggerName());
            Assertions.assertTrue(warning.getMessage().getFormattedMessage().contains("no available compute nodes"));
            Assertions.assertNull(warning.getThrown());
        }
    }

    @Test
    public void compactionFallsBackOnProviderFailureAndInSharedNothingMode(@Injectable ComputeResourceProvider delegate) {
        Config.lake_compaction_warehouse = "research";
        Warehouse warehouse = manager.getWarehouse(research);
        MultiWarehouseManager failingManager = new MultiWarehouseManager(fe, delegate);
        failingManager.replayCreateWarehouse(warehouse);
        long groupId = warehouse.getAnyWorkerGroupId();
        new Expectations() {
            {
                delegate.ofComputeResource(research, groupId);
                result = new IllegalStateException("provider failure");
                times = 2;
            }
        };
        List<LogEvent> warnings = captureRoutingWarnings(() -> {
            Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE, failingManager.getCompactionComputeResource(1L));
            Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE, failingManager.getCompactionComputeResource(2L));
            fe.sharedData = false;
            Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE, failingManager.getCompactionComputeResource(3L));
        });
        Assertions.assertEquals(1, warnings.size());
        Assertions.assertTrue(warnings.get(0).getMessage().getFormattedMessage().contains("provider failure"));
        Assertions.assertNull(warnings.get(0).getThrown());
    }

    private static List<LogEvent> captureRoutingWarnings(Runnable work) {
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender("routing-warnings", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                events.add(event.toImmutable());
            }
        };
        Map<Logger, Level> levels = new HashMap<>();
        for (Class<?> source : Arrays.asList(MultiWarehouseManager.class, WarehouseComputeResourceProvider.class)) {
            Logger logger = (Logger) LogManager.getLogger(source);
            levels.put(logger, logger.getLevel());
        }
        appender.start();
        try {
            // Adding an appender can refresh both loggers and reset levels set on either one.
            levels.keySet().forEach(logger -> logger.addAppender(appender));
            levels.keySet().forEach(logger -> logger.setLevel(Level.WARN));
            work.run();
        } finally {
            levels.keySet().forEach(logger -> logger.removeAppender(appender));
            levels.forEach(Logger::setLevel);
            appender.stop();
        }
        return events;
    }

    @Test
    public void onlyCompactionFallsBackWhenTheConfiguredWarehouseHasNoLiveNodes() {
        fe.sessionUser = "analyst";
        Config.lake_background_warehouse = "research";
        Config.lake_compaction_warehouse = "research";
        provider.hasLiveNodes = false;

        ErrorReportException error = Assertions.assertThrows(ErrorReportException.class,
                () -> manager.getBackgroundComputeResource(42L));
        Assertions.assertEquals(ErrorCode.ERR_WAREHOUSE_UNAVAILABLE, error.getErrorCode());
        Assertions.assertSame(WarehouseManager.DEFAULT_RESOURCE, manager.getCompactionComputeResource(42L));
        Assertions.assertEquals(Arrays.asList(research, research), provider.served);

        provider.hasLiveNodes = true;
        Assertions.assertEquals(research, manager.getCompactionComputeResource(42L).getWarehouseId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void everySessionAcquisitionOverloadChecksUsage() {
        fe.sessionUser = "analyst";
        ComputeResource previous = WarehouseComputeResource.of(research);

        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.acquireComputeResource(research));
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> manager.acquireComputeResource(research, previous));
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> manager.acquireComputeResource(CRAcquireContext.of(research)));

        Assertions.assertEquals(Arrays.asList("analyst on research", "analyst on research", "analyst on research"),
                fe.usageChecks);
        Assertions.assertTrue(provider.served.isEmpty());
    }

    @Test
    public void theCheckCanBeSwitchedOff() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false, false));
        fe.sessionUser = "analyst";

        ComputeResource resource = manager.acquireComputeResource(research);

        Assertions.assertEquals(research, resource.getWarehouseId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    /**
     * The gate has nothing to check for an id it does not know. The FE's own code reports it, as before.
     */
    @Test
    public void anUnknownWarehouseIsReportedByTheFe() {
        fe.sessionUser = "analyst";

        ErrorReportException e = Assertions.assertThrows(ErrorReportException.class,
                () -> manager.acquireComputeResource(424242L));

        Assertions.assertTrue(e.getMessage().contains("id: 424242"), e.getMessage());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    // ------------------------------------------------------------------ a session reuses what it acquired

    /**
     * The first statement of a session on a warehouse. As in the FE, the session first builds the resource
     * object it will keep, then acquires through it.
     */
    private ComputeResource firstStatement(long warehouseId) {
        ComputeResource held = WarehouseComputeResource.of(warehouseId);
        fe.sessionResource = held;
        manager.acquireComputeResource(warehouseId, held);
        return held;
    }

    /**
     * A session acquires its compute resource once. Before every later statement the FE asks whether that
     * resource is still available, and that question is checked too.
     */
    @Test
    public void everyLaterStatementOfASessionIsCheckedAgain() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);

        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource));

        Assertions.assertEquals(Arrays.asList("analyst on research", "analyst on research", "analyst on research"),
                fe.usageChecks, "once to acquire, once per later statement");
    }

    /**
     * The FE asks many times in one statement. Only the first question of a statement is checked.
     */
    @Test
    public void aStatementIsCheckedOnlyOnce() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);

        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertEquals(1, fe.usageChecks.size(), "the statement that acquired has passed already");

        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertEquals(2, fe.usageChecks.size(), "one more for the next statement, however often the FE asks");
    }

    /**
     * REVOKE must reach a connection that is already open. The session gets the access-denied error, not "no
     * nodes", and works again once the privilege is back.
     */
    @Test
    public void aRevokeStopsAnOpenSessionAtItsNextStatement() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);

        fe.usageGrants.clear();
        fe.nextStatement();
        FakeFeEnvironment.AccessDenied denied = Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> manager.isResourceAvailable(resource));
        Assertions.assertTrue(denied.getMessage().contains("analyst needs USAGE on WAREHOUSE research"), denied.getMessage());
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(resource),
                "a refused statement has not passed: it is refused again when the FE asks again");
        fe.nextStatement();
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(resource),
                "and so is every statement after it");

        fe.usageGrants.add("analyst on research");
        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource));
    }

    /**
     * The FE builds the profile of an INSERT after the commit, and asks for the session's resource to do it. A
     * REVOKE that arrived while the INSERT ran must not turn the committed INSERT into a failed one. It takes
     * effect at the next statement.
     */
    @Test
    public void aRevokeDuringAStatementDoesNotChangeItsResult() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);
        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource), "the statement passes at its start");

        fe.usageGrants.clear();
        fe.usageChecks.clear();

        Assertions.assertTrue(manager.isResourceAvailable(resource), "asked again while the statement runs");
        fe.statementFinished = true;
        Assertions.assertTrue(manager.isResourceAvailable(resource), "and again after it has its result, for the profile");
        Assertions.assertTrue(fe.usageChecks.isEmpty(), "the statement is not checked a second time");

        fe.nextStatement();
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(resource),
                "the next statement is refused");
    }

    /**
     * The same for the first statement of a session, which passed the check when it acquired.
     */
    @Test
    public void aRevokeDoesNotStopTheStatementThatAcquiredEither() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);

        fe.usageGrants.clear();
        fe.usageChecks.clear();

        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    /**
     * A statement that has its result when it is checked for the first time: raising the error now would
     * replace that result. The answer is "not available" instead, which makes the session drop the resource
     * without an error.
     */
    @Test
    public void aCheckAfterTheStatementFinishedRaisesNothing() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);
        fe.nextStatement();
        fe.usageGrants.clear();
        fe.statementFinished = true;

        Assertions.assertFalse(manager.isResourceAvailable(resource));
        Assertions.assertFalse(manager.isResourceAvailable(resource), "a refusal is not remembered as a pass");

        fe.nextStatement();
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(resource),
                "a statement that is still running is refused with the error");
    }

    @Test
    public void aCheckAfterTheStatementFinishedPassesWithThePrivilege() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);
        fe.nextStatement();
        fe.statementFinished = true;
        fe.usageChecks.clear();

        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertEquals(1, fe.usageChecks.size(), "checked once, then remembered");

        provider.hasLiveNodes = false;
        Assertions.assertFalse(manager.isResourceAvailable(resource), "the FE's own answer still counts");
    }

    /**
     * Without a statement id there is nothing to remember a pass by, so every question is checked.
     */
    @Test
    public void aSessionWithoutAStatementIdIsCheckedEveryTime() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        fe.statement = null;
        ComputeResource resource = firstStatement(research);

        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertEquals(3, fe.usageChecks.size(), "once to acquire, once per question");

        fe.usageGrants.clear();
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(resource));
    }

    /**
     * Direct acquisitions check USAGE independently on every call. The session records its own approval
     * for the current query ID and warehouse and can reuse it across retries.
     */
    @Test
    public void directAcquisitionAndSessionAcquisitionCheckUsageSeparately() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource held = WarehouseComputeResource.of(research);
        fe.sessionResource = held;

        manager.acquireComputeResource(research, WarehouseComputeResource.of(research));
        fe.usageChecks.clear();

        Assertions.assertTrue(manager.isResourceAvailable(held));
        Assertions.assertEquals(Collections.singletonList("analyst on research"), fe.usageChecks);

        fe.usageGrants.clear();
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.acquireComputeResource(research));
    }

    /**
     * USAGE on one warehouse is not a pass for a resource of another, even when the session acquires through
     * that resource.
     */
    @Test
    public void acquiringOnAnotherWarehouseIsNotAPassForTheSessionsResource() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on etl");
        ComputeResource held = WarehouseComputeResource.of(research);
        fe.sessionResource = held;

        manager.acquireComputeResource(etl, held);

        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(held));
    }

    @Test
    public void anApprovalIsPrivateToTheSessionEvenForTheSameQueryId() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        firstStatement(research);

        fe.session = new Object();
        fe.usageGrants.clear();
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> firstStatement(research));
    }

    @Test
    public void aStatementChecksEachWarehouseItUses() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        firstStatement(research);
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> firstStatement(etl));

        fe.usageGrants.add("analyst on etl");
        firstStatement(etl);
        fe.usageGrants.clear();
        firstStatement(research);
        Assertions.assertEquals(Arrays.asList(research, etl, research), provider.served);
        Assertions.assertEquals(Arrays.asList("analyst on research", "analyst on etl", "analyst on etl"), fe.usageChecks);
    }

    @Test
    public void automaticRetrySurvivesRevokeButTheNextStatementIsDenied(@Mocked GlobalStateMgr state,
                                                                        @Mocked Authorizer authorizer) throws Exception {
        MultiWarehouseManager sessionManager = new MultiWarehouseManager(FeEnvironment.live(), provider);
        fe.replayInto(sessionManager);
        SessionVariable variables = new SessionVariable();
        variables.setWarehouseName("research");
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getVariableMgr().newSessionVariable();
                result = variables;
                state.getWarehouseMgr();
                result = sessionManager;
            }
        };

        ConnectContext session = new ConnectContext();
        session.setCurrentUserIdentity(new UserIdentity("analyst", "%"));
        session.setQueryId(UUID.randomUUID());
        session.setExecutionId(UUIDUtil.toTUniqueId(session.getQueryId()));
        try (var ignored = session.bindScope()) {
            LazyComputeResource initial = (LazyComputeResource) session.getCurrentComputeResource();
            Assertions.assertEquals(research, initial.get().getWarehouseId());
            new Verifications() {
                {
                    Authorizer.checkWarehouseAction(session, "research", PrivilegeType.USAGE);
                    times = 1;
                }
            };

            // Revoke USAGE after the first attempt has acquired its resource.
            new Expectations() {
                {
                    Authorizer.checkWarehouseAction(session, "research", PrivilegeType.USAGE);
                    result = new AccessDeniedException();
                }
            };

            // StmtExecutor refreshes compute and changes the execution ID for a retry of the same query.
            session.ensureCurrentComputeResourceAvailable();
            session.setExecutionId(UUIDUtil.toTUniqueId(UUID.randomUUID()));
            LazyComputeResource retry = (LazyComputeResource) session.getCurrentComputeResource();
            Assertions.assertNotSame(initial, retry);
            Assertions.assertEquals(research, retry.get().getWarehouseId());
            Assertions.assertEquals(Arrays.asList(research, research), provider.served);

            session.setQueryId(UUID.randomUUID());
            session.setExecutionId(UUIDUtil.toTUniqueId(session.getQueryId()));
            ErrorReportException denied = Assertions.assertThrows(ErrorReportException.class, session::getCurrentComputeResource);
            Assertions.assertEquals(ErrorCode.ERR_ACCESS_DENIED, denied.getErrorCode());
        }
    }

    /**
     * The privilege check comes on top of the FE's own answer. A warehouse whose nodes are all down is still
     * reported as not available.
     */
    @Test
    public void aWarehouseWithoutLiveNodesIsStillNotAvailable() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);

        provider.hasLiveNodes = false;

        Assertions.assertFalse(manager.isResourceAvailable(resource));
        fe.nextStatement();
        Assertions.assertFalse(manager.isResourceAvailable(resource));
    }

    /**
     * A load job runs in an internal session that carries the user's name but not every role the user logged
     * in with. The FE hands that session the job's stored resource. Checking it there would refuse loads that
     * were allowed when they were submitted, so only a resource the session acquired itself is checked.
     */
    @Test
    public void aResourceHandedToASessionIsNotChecked() {
        fe.sessionUser = "analyst";
        ComputeResource stored = WarehouseComputeResource.of(research);

        Assertions.assertTrue(manager.isResourceAvailable(stored));

        fe.sessionResource = WarehouseComputeResource.of(research);
        Assertions.assertTrue(manager.isResourceAvailable(stored), "the session holds another object for the same warehouse");
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void reuseOnTheDefaultWarehouseIsNeverChecked() {
        fe.sessionUser = "analyst";
        ComputeResource resource = firstStatement(WarehouseManager.DEFAULT_WAREHOUSE_ID);

        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    /**
     * Publish and other FE work ask the same question from threads without a user session. They get the FE's
     * own answer.
     */
    @Test
    public void availabilityAskedByBackgroundWorkIsNotChecked() {
        fe.sessionUser = null;
        ComputeResource resource = firstStatement(research);

        fe.nextStatement();
        Assertions.assertTrue(manager.isResourceAvailable(resource));
        provider.hasLiveNodes = false;
        Assertions.assertFalse(manager.isResourceAvailable(resource));
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    @Test
    public void theSwitchAlsoTurnsOffTheCheckOnReuse() {
        fe.sessionUser = "analyst";
        fe.usageGrants.add("analyst on research");
        ComputeResource resource = firstStatement(research);
        fe.usageGrants.clear();
        fe.usageChecks.clear();
        fe.nextStatement();

        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false, false));

        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(fe.usageChecks.isEmpty());
    }

    /**
     * A warehouse dropped between two statements of a session: the next statement gets the FE's "not exist"
     * error, not a privilege error.
     */
    @Test
    public void aDroppedWarehouseIsReportedAsMissingNotAsDenied() throws Exception {
        fe.sessionUser = "analyst";
        manager.dropWarehouse(new com.starrocks.sql.ast.warehouse.DropWarehouseStmt(false, "etl"));

        ErrorReportException e = Assertions.assertThrows(ErrorReportException.class,
                () -> manager.acquireComputeResource(etl));

        Assertions.assertTrue(e.getMessage().contains("id: " + etl), e.getMessage());
    }
}
