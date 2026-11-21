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

import com.starrocks.common.Config;
import com.starrocks.common.ErrorReportException;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.CRAcquireContext;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.ComputeResourceProvider;
import com.starrocks.warehouse.cngroup.WarehouseComputeResource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The gate: the USAGE check on the one method every query, INSERT and Stream Load calls to get compute nodes.
 *
 * <p>The tests run the FE's own acquire code in shared-data mode. Only the last step, finding live nodes, is
 * replaced, by a provider that records which warehouse it was asked for.
 */
public class WarehouseGateTest {

    /** Hands out a compute resource for any warehouse and remembers the request. */
    private static final class RecordingProvider implements ComputeResourceProvider {
        final List<Long> served = new ArrayList<>();

        @Override
        public Optional<ComputeResource> acquireComputeResource(Warehouse warehouse, CRAcquireContext acquireContext) {
            served.add(warehouse.getId());
            return Optional.of(WarehouseComputeResource.of(warehouse.getId()));
        }

        @Override
        public ComputeResource ofComputeResource(long warehouseId, long workGroupId) {
            return WarehouseComputeResource.of(warehouseId);
        }

        @Override
        public List<ComputeResource> getComputeResources(Warehouse warehouse) {
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
    private FakeFeEnvironment fe;
    private RecordingProvider provider;
    private UserWarehouseManager manager;
    private long research;
    private long etl;

    @BeforeEach
    public void before() throws Exception {
        savedRunMode = Config.run_mode;
        Config.run_mode = "shared_data";
        RunMode.detectRunMode();
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false));

        fe = new FakeFeEnvironment();
        provider = new RecordingProvider();
        manager = new UserWarehouseManager(fe, provider);
        manager.initDefaultWarehouse();
        manager.createWarehouse(new CreateWarehouseStmt(false, "research", null, null));
        manager.createWarehouse(new CreateWarehouseStmt(false, "etl", null, null));
        research = manager.getWarehouse("research").getId();
        etl = manager.getWarehouse("etl").getId();
    }

    @AfterEach
    public void after() {
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

    /**
     * Every user may use the default warehouse, as in a stock FE. Its requests are not checked at all.
     */
    @Test
    public void theDefaultWarehouseIsNeverChecked() {
        fe.sessionUser = "analyst";

        ComputeResource resource = manager.acquireComputeResource(WarehouseManager.DEFAULT_WAREHOUSE_ID);

        Assertions.assertEquals(WarehouseManager.DEFAULT_WAREHOUSE_ID, resource.getWarehouseId());
        Assertions.assertTrue(fe.usageChecks.isEmpty());
        Assertions.assertEquals(Collections.singletonList(WarehouseManager.DEFAULT_WAREHOUSE_ID), provider.served);
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

    /**
     * The FE reaches the manager through three methods. All of them must end in the gate.
     */
    @Test
    public void everyWayIntoTheManagerPassesTheGate() {
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

    /** The first statement of a session: it acquires a resource and from then on holds it. */
    private ComputeResource firstStatement(long warehouseId) {
        ComputeResource resource = manager.acquireComputeResource(warehouseId);
        fe.sessionResource = resource;
        return resource;
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

        Assertions.assertTrue(manager.isResourceAvailable(resource));
        Assertions.assertTrue(manager.isResourceAvailable(resource));

        Assertions.assertEquals(Arrays.asList("analyst on research", "analyst on research", "analyst on research"),
                fe.usageChecks, "once to acquire, once per later statement");
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
        FakeFeEnvironment.AccessDenied denied = Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class,
                () -> manager.isResourceAvailable(resource));
        Assertions.assertTrue(denied.getMessage().contains("analyst needs USAGE on WAREHOUSE research"), denied.getMessage());
        Assertions.assertThrows(FakeFeEnvironment.AccessDenied.class, () -> manager.isResourceAvailable(resource),
                "and at every statement after it");

        fe.usageGrants.add("analyst on research");
        Assertions.assertTrue(manager.isResourceAvailable(resource));
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
