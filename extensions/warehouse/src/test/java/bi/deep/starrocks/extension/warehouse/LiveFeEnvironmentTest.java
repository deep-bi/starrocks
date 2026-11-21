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

import com.staros.proto.WorkerGroupDetailInfo;
import com.starrocks.authorization.AccessDeniedException;
import com.starrocks.authorization.AuthorizationMgr;
import com.starrocks.authorization.ObjectType;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.ResourceGroup;
import com.starrocks.catalog.ResourceGroupMgr;
import com.starrocks.catalog.UserIdentity;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.lake.StarOSAgent;
import com.starrocks.metric.Metric;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.OperationType;
import com.starrocks.persist.WALApplier;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.SystemInfoService;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.LazyComputeResource;
import com.starrocks.warehouse.cngroup.WarehouseComputeResource;
import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link LiveFeEnvironment} is the only class that touches the FE's global state. Each of its methods is one
 * delegation, and these tests pin down which FE call each one makes. The FE itself is mocked here; the
 * integration tests exercise the same calls against a real FE and StarMgr.
 */
public class LiveFeEnvironmentTest {
    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private StarOSAgent starOSAgent;
    @Mocked
    private EditLog editLog;
    @Mocked
    private NodeMgr nodeMgr;
    @Mocked
    private SystemInfoService clusterInfo;
    @Mocked
    private AuthorizationMgr authorizationMgr;

    private final FeEnvironment live = FeEnvironment.live();

    private void wireGlobalState() {
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = globalStateMgr;
                minTimes = 0;
                globalStateMgr.getStarOSAgent();
                result = starOSAgent;
                minTimes = 0;
                globalStateMgr.getEditLog();
                result = editLog;
                minTimes = 0;
                globalStateMgr.getNodeMgr();
                result = nodeMgr;
                minTimes = 0;
                nodeMgr.getClusterInfo();
                result = clusterInfo;
                minTimes = 0;
                GlobalStateMgr.getServingState();
                result = globalStateMgr;
                minTimes = 0;
                globalStateMgr.getAuthorizationMgr();
                result = authorizationMgr;
                minTimes = 0;
                globalStateMgr.isReady();
                result = true;
                minTimes = 0;
            }
        };
    }

    @Test
    public void thereIsOneLiveEnvironment() {
        Assertions.assertSame(LiveFeEnvironment.INSTANCE, live);
        Assertions.assertSame(live, FeEnvironment.live());
    }

    @Test
    public void theClockIsTheSystemClock() {
        long before = System.currentTimeMillis();
        long now = live.currentTimeMillis();
        Assertions.assertTrue(now >= before && now <= System.currentTimeMillis());
    }

    @Test
    public void sharedDataIsWhatTheFeReports(@Mocked RunMode runMode) {
        new Expectations() {
            {
                RunMode.isSharedDataMode();
                returns(true, false);
            }
        };

        Assertions.assertTrue(live.isSharedData());
        Assertions.assertFalse(live.isSharedData());
    }

    @Test
    public void idsComeFromTheFeIdGenerator() {
        wireGlobalState();
        new Expectations() {
            {
                globalStateMgr.getNextId();
                result = 12345L;
            }
        };

        Assertions.assertEquals(12345L, live.nextId());
    }

    @Test
    public void workerGroupsAreManagedThroughStarMgr() throws Exception {
        wireGlobalState();
        new Expectations() {
            {
                starOSAgent.createWorkerGroup(LiveFeEnvironment.WORKER_GROUP_SIZE, 3);
                result = 501L;
            }
        };

        Assertions.assertEquals(501L, live.createWorkerGroup(3));
        live.updateWorkerGroup(501L, 2);
        live.deleteWorkerGroup(501L);

        new Verifications() {
            {
                starOSAgent.updateWorkerGroup(501L, 2);
                times = 1;
                starOSAgent.deleteWorkerGroup(501L);
                times = 1;
            }
        };
    }

    @Test
    public void theReplicaNumberOfAWorkerGroupIsReadFromStarMgr() throws Exception {
        wireGlobalState();
        new Expectations() {
            {
                starOSAgent.getWorkerGroupInfo(501L);
                result = WorkerGroupDetailInfo.newBuilder().setGroupId(501L).setReplicaNumber(3).build();
            }
        };

        Assertions.assertEquals(3, live.replicaNumberInStarMgr(501L));
    }

    /**
     * While an FE takes over as leader it is "not ready" from before the last replay until after it has become
     * the leader. Leader and ready together therefore mean: leader, with the whole edit log replayed.
     */
    @Test
    public void theActiveLeaderIsALeaderThatHasFinishedTakingOver() {
        wireGlobalState();
        new Expectations() {
            {
                globalStateMgr.isLeader();
                returns(false, true, true);
                globalStateMgr.isReady();
                returns(false, true);
            }
        };

        Assertions.assertFalse(live.isActiveLeader(), "a follower");
        Assertions.assertFalse(live.isActiveLeader(), "the leader, still taking over");
        Assertions.assertTrue(live.isActiveLeader());
    }

    @Test
    public void theServingManagerIsReportedOnlyWhenItIsTheExtensions() {
        wireGlobalState();
        UserWarehouseManager ours = new UserWarehouseManager(new FakeFeEnvironment());
        new Expectations() {
            {
                globalStateMgr.getWarehouseMgr();
                returns(new WarehouseManager(), ours);
            }
        };

        Assertions.assertNull(live.servingManager(), "the stock manager");
        Assertions.assertSame(ours, live.servingManager());
    }

    @Test
    public void journalEntriesGoToTheEditLog() {
        wireGlobalState();
        Object entry = new Object();
        WALApplier applier = wal -> {
        };

        live.writeJournal(OperationType.OP_CREATE_WAREHOUSE, entry, applier);

        new Verifications() {
            {
                editLog.logJsonObject(OperationType.OP_CREATE_WAREHOUSE, entry, applier);
                times = 1;
            }
        };
    }

    @Test
    public void nodesAreFilteredByWarehouse() {
        wireGlobalState();
        ComputeNode inResearch = new ComputeNode(1L, "cn-a", 9050);
        inResearch.setWarehouseId(10001L);
        ComputeNode inDefault = new ComputeNode(2L, "cn-b", 9050);
        inDefault.setWarehouseId(0L);
        Backend backendInResearch = new Backend(3L, "be-a", 9050);
        backendInResearch.setWarehouseId(10001L);
        new Expectations() {
            {
                clusterInfo.backendAndComputeNodeStream();
                result = Stream.of(inResearch, inDefault, backendInResearch);
            }
        };

        List<Long> ids = live.nodesOf(10001L).stream().map(ComputeNode::getId).collect(Collectors.toList());

        Assertions.assertEquals(java.util.Arrays.asList(1L, 3L), ids);
    }

    @Test
    public void workerIdsComeFromStarMgr() {
        wireGlobalState();
        new Expectations() {
            {
                starOSAgent.getWorkerIdByNodeId(20001L);
                result = 7L;
            }
        };

        Assertions.assertEquals(7L, live.workerIdOf(20001L));
    }

    @Test
    public void allNodesAreTheBackendsAndComputeNodesOfTheCluster() {
        wireGlobalState();
        ComputeNode computeNode = new ComputeNode(1L, "cn-a", 9050);
        Backend backend = new Backend(2L, "be-a", 9050);
        new Expectations() {
            {
                clusterInfo.backendAndComputeNodeStream();
                result = Stream.of(computeNode, backend);
            }
        };

        Assertions.assertEquals(java.util.Arrays.asList(computeNode, backend), live.allNodes());
    }

    @Test
    public void knownWarehousesAreThoseOfTheServingManager() {
        wireGlobalState();
        WarehouseManager serving = new WarehouseManager();
        serving.initDefaultWarehouse();
        serving.addWarehouse(new UserWarehouse(10001L, "research", null, 501L, 1, 1_000L, 1_000L));
        new Expectations() {
            {
                globalStateMgr.getWarehouseMgr();
                result = serving;
            }
        };

        Assertions.assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(0L, 10001L)),
                new java.util.HashSet<>(live.knownWarehouseIds()));
    }

    /**
     * Only resource groups with a 'warehouses' property are reported. A group dropped between the listing of
     * the ids and the lookup is skipped.
     */
    @Test
    public void resourceGroupWarehousesAreReadFromTheFe(@Mocked ResourceGroupMgr resourceGroupMgr) {
        wireGlobalState();
        new Expectations() {
            {
                globalStateMgr.getResourceGroupMgr();
                result = resourceGroupMgr;
                resourceGroupMgr.getResourceGroupIds();
                result = java.util.Arrays.asList(1L, 2L, 3L, 4L, 5L);
                resourceGroupMgr.getResourceGroup(1L);
                result = resourceGroup("rg_reports", java.util.Arrays.asList("etl", "research"));
                resourceGroupMgr.getResourceGroup(2L);
                result = resourceGroup("rg_everywhere", null);
                resourceGroupMgr.getResourceGroup(3L);
                result = resourceGroup("rg_unbound", new java.util.ArrayList<>());
                resourceGroupMgr.getResourceGroup(4L);
                result = null;
                resourceGroupMgr.getResourceGroup(5L);
                result = resourceGroup("rg_adhoc", java.util.Collections.singletonList("research"));
            }
        };

        java.util.Map<String, List<String>> byGroup = live.warehousesByResourceGroup();

        Assertions.assertEquals(java.util.Arrays.asList("rg_adhoc", "rg_reports"), new java.util.ArrayList<>(byGroup.keySet()));
        Assertions.assertEquals(java.util.Arrays.asList("etl", "research"), byGroup.get("rg_reports"));
        Assertions.assertEquals(java.util.Collections.singletonList("research"), byGroup.get("rg_adhoc"));
    }

    private static ResourceGroup resourceGroup(String name, List<String> warehouses) {
        ResourceGroup group = new ResourceGroup();
        group.setName(name);
        group.setWarehouses(warehouses);
        return group;
    }

    /**
     * A periodic task runs on an FE daemon thread, and only once the serving FE state is ready. A second task
     * with the same name is refused, which is what keeps checkpoint copies of the manager from starting more.
     */
    @Test
    public void aPeriodicTaskStartsOncePerName() throws Exception {
        wireGlobalState();
        CountDownLatch firstRound = new CountDownLatch(1);
        String name = "live-fe-environment-test-task";
        // An interval this long means exactly one round within the life of the test JVM.
        long never = Long.MAX_VALUE / 4;

        Assertions.assertTrue(live.runPeriodically(name, never, firstRound::countDown));
        Assertions.assertTrue(firstRound.await(20, TimeUnit.SECONDS), "the task did not run");

        Assertions.assertFalse(live.runPeriodically(name, never, () -> {
            throw new AssertionError("a second task with the same name must not start");
        }));
    }

    @Test
    public void gaugesGoToTheFeMetricRegistry(@Mocked MetricRepo metricRepo) {
        live.registerGauge("live_fe_environment_test_gauge", "a description", () -> 42L);

        new Verifications() {
            {
                Metric<?> metric;
                MetricRepo.addMetric(metric = withCapture());
                times = 1;
                Assertions.assertEquals("live_fe_environment_test_gauge", metric.getName());
                Assertions.assertEquals(Metric.MetricType.GAUGE, metric.getType());
                Assertions.assertEquals(42L, metric.getValue());
            }
        };
    }

    // ------------------------------------------------------------------ the gate's view of the session

    @Test
    public void aUserSessionIsAContextWithAnIdentity(@Mocked ConnectContext context) {
        new Expectations() {
            {
                ConnectContext.get();
                returns(null, context, context);
                context.getCurrentUserIdentity();
                returns(null, UserIdentity.ROOT);
            }
        };

        Assertions.assertFalse(live.hasUserSession(), "no session on the thread: background work");
        Assertions.assertFalse(live.hasUserSession(), "a session nobody has logged in to");
        Assertions.assertTrue(live.hasUserSession());
    }

    /**
     * "Acquired by the session" means two things at once: the session on this thread holds this very object,
     * and the object is of the type a session builds when it acquires for itself.
     */
    @Test
    public void aSessionAcquiredItsResourceWhenItHoldsTheLazyObjectItBuilt(@Mocked ConnectContext context) {
        ComputeResource acquired = LazyComputeResource.of(10001L, () -> WarehouseComputeResource.of(10001L));
        ComputeResource anotherLazy = LazyComputeResource.of(10001L, () -> WarehouseComputeResource.of(10001L));
        ComputeResource handedIn = WarehouseComputeResource.of(10001L);
        new Expectations() {
            {
                ConnectContext.get();
                returns(null, context, context, context);
                context.getCurrentComputeResourceNoAcquire();
                returns(acquired, acquired);
                minTimes = 0;
            }
        };

        Assertions.assertFalse(live.isAcquiredBySession(acquired), "no session on the thread: background work");
        Assertions.assertFalse(live.isAcquiredBySession(handedIn), "a stored resource, as a load job's session gets");
        Assertions.assertFalse(live.isAcquiredBySession(anotherLazy), "a lazy resource, but not the one this session holds");
        Assertions.assertTrue(live.isAcquiredBySession(acquired));
    }

    @Test
    public void usageIsCheckedByTheFeAuthorizer(@Mocked ConnectContext context, @Mocked Authorizer authorizer)
            throws Exception {
        new Expectations() {
            {
                ConnectContext.get();
                result = context;
            }
        };

        live.requireUsage("research");

        new Verifications() {
            {
                Authorizer.checkWarehouseAction(context, "research", PrivilegeType.USAGE);
                times = 1;
            }
        };
    }

    /**
     * A missing privilege must surface as the error the FE raises for SET warehouse: the same code and the same
     * text. The text is compared with what the FE's own reporter produces, so a change in the FE shows up here.
     */
    @Test
    public void aMissingPrivilegeIsReportedLikeTheFeDoes(@Mocked ConnectContext context,
                                                        @Mocked Authorizer authorizer) throws Exception {
        wireGlobalState();
        java.util.Set<Long> roleIds = new java.util.HashSet<>(java.util.Arrays.asList(7L));
        new Expectations() {
            {
                ConnectContext.get();
                result = context;
                context.getCurrentUserIdentity();
                result = UserIdentity.ROOT;
                context.getCurrentRoleIds();
                result = roleIds;
                Authorizer.checkWarehouseAction(context, "research", PrivilegeType.USAGE);
                result = new AccessDeniedException();
                authorizationMgr.getRoleNamesByRoleIds(roleIds);
                result = java.util.Arrays.asList("analyst_role");
                authorizationMgr.getInactivatedRoleNamesByUser(UserIdentity.ROOT, (List<String>) any);
                result = java.util.Arrays.asList("etl_role");
            }
        };

        ErrorReportException ours = Assertions.assertThrows(ErrorReportException.class,
                () -> live.requireUsage("research"));

        Assertions.assertEquals(ErrorCode.ERR_ACCESS_DENIED, ours.getErrorCode());
        Assertions.assertTrue(ours.getMessage().contains("the USAGE privilege(s) on WAREHOUSE research"), ours.getMessage());
        Assertions.assertTrue(ours.getMessage().contains("analyst_role"), ours.getMessage());
        Assertions.assertTrue(ours.getMessage().contains("etl_role"), ours.getMessage());

        ErrorReportException stock = Assertions.assertThrows(ErrorReportException.class,
                () -> AccessDeniedException.reportAccessDenied(null, UserIdentity.ROOT, roleIds,
                        PrivilegeType.USAGE.name(), ObjectType.WAREHOUSE.name(), "research"));
        Assertions.assertEquals(stock.getMessage(), ours.getMessage());
        Assertions.assertEquals(stock.getErrorCode(), ours.getErrorCode());
    }

    @Test
    public void aUserWithoutAnyRoleIsReportedWithNone(@Mocked ConnectContext context, @Mocked Authorizer authorizer)
            throws Exception {
        wireGlobalState();
        new Expectations() {
            {
                ConnectContext.get();
                result = context;
                Authorizer.checkWarehouseAction(context, "research", PrivilegeType.USAGE);
                result = new AccessDeniedException();
                authorizationMgr.getRoleNamesByRoleIds((java.util.Set<Long>) any);
                result = new java.util.ArrayList<String>();
                authorizationMgr.getInactivatedRoleNamesByUser((UserIdentity) any, (List<String>) any);
                result = new java.util.ArrayList<String>();
            }
        };

        ErrorReportException e = Assertions.assertThrows(ErrorReportException.class, () -> live.requireUsage("research"));

        Assertions.assertTrue(e.getMessage().contains("NONE"), e.getMessage());
        Assertions.assertFalse(e.getMessage().contains("[]"), e.getMessage());
    }

    /**
     * A warehouse that no manager has attached yet, for example one Gson has just built, falls back to the live
     * environment.
     */
    @Test
    public void anUnattachedWarehouseUsesTheLiveEnvironment() {
        wireGlobalState();
        ComputeNode node = new ComputeNode(1L, "cn-a", 9050);
        node.setWarehouseId(10001L);
        node.setAlive(true);
        new Expectations() {
            {
                clusterInfo.backendAndComputeNodeStream();
                result = Stream.of(node);
            }
        };

        UserWarehouse unattached = new UserWarehouse(10001L, "research", null, 501L, 1, 1_000L, 1_000L);

        Assertions.assertEquals(WarehouseRows.STATE_AVAILABLE, unattached.getWarehouseInfo().get(2));
    }
}
