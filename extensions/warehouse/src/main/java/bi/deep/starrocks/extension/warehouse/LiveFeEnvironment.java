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
import com.starrocks.authorization.AuthorizationMgr;
import com.starrocks.authorization.ObjectType;
import com.starrocks.authorization.PrivilegeType;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.ResourceGroup;
import com.starrocks.catalog.ResourceGroupMgr;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.common.util.FrontendDaemon;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.load.routineload.RoutineLoadJob;
import com.starrocks.metric.GaugeMetric;
import com.starrocks.metric.Metric;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.WALApplier;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.analyzer.Authorizer;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.LazyComputeResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * {@link FeEnvironment} backed by the FE this extension is loaded into.
 *
 * <p>{@code GlobalStateMgr.getCurrentState()} is resolved on every call. The checkpoint thread runs with its own
 * {@code GlobalStateMgr}, and that method returns the right one for the calling thread.
 */
final class LiveFeEnvironment implements FeEnvironment {
    static final LiveFeEnvironment INSTANCE = new LiveFeEnvironment();

    /**
     * StarMgr wants a size label on every worker group. The extension does not expose it and always sends
     * {@code x0}.
     */
    static final String WORKER_GROUP_SIZE = "x0";

    /** Names of the periodic tasks started in this FE process. */
    private final Set<String> startedTasks = ConcurrentHashMap.newKeySet();

    private LiveFeEnvironment() {
    }

    @Override
    public boolean isSharedData() {
        return RunMode.isSharedDataMode();
    }

    @Override
    public long nextId() {
        return GlobalStateMgr.getCurrentState().getNextId();
    }

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long createWorkerGroup(int replicaNumber) throws DdlException {
        return GlobalStateMgr.getCurrentState().getStarOSAgent().createWorkerGroup(WORKER_GROUP_SIZE, replicaNumber);
    }

    @Override
    public void updateWorkerGroup(long workerGroupId, int replicaNumber) throws DdlException {
        GlobalStateMgr.getCurrentState().getStarOSAgent().updateWorkerGroup(workerGroupId, replicaNumber);
    }

    @Override
    public void deleteWorkerGroup(long workerGroupId) throws DdlException {
        GlobalStateMgr.getCurrentState().getStarOSAgent().deleteWorkerGroup(workerGroupId);
    }

    @Override
    public int replicaNumberInStarMgr(long workerGroupId) throws DdlException {
        return GlobalStateMgr.getCurrentState().getStarOSAgent().getWorkerGroupInfo(workerGroupId).getReplicaNumber();
    }

    /**
     * An FE that takes over as leader first stops being ready, then replays the rest of the edit log, then
     * becomes the leader, and only then is ready again. Asking for both leaves no moment in which it counts as
     * the leader while it still replays.
     */
    @Override
    public boolean isActiveLeader() {
        GlobalStateMgr serving = GlobalStateMgr.getServingState();
        return serving.isLeader() && serving.isReady();
    }

    @Override
    public MultiWarehouseManager servingManager() {
        WarehouseManager manager = GlobalStateMgr.getServingState().getWarehouseMgr();
        return manager instanceof MultiWarehouseManager ? (MultiWarehouseManager) manager : null;
    }

    @Override
    public void writeJournal(short operation, Object entry, WALApplier afterWrite) {
        GlobalStateMgr.getCurrentState().getEditLog().logJsonObject(operation, entry, afterWrite);
    }

    @Override
    public List<ComputeNode> nodesOf(long warehouseId) {
        return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().backendAndComputeNodeStream()
                .filter(node -> node.getWarehouseId() == warehouseId)
                .collect(Collectors.toList());
    }

    @Override
    public long workerIdOf(long nodeId) {
        return GlobalStateMgr.getCurrentState().getStarOSAgent().getWorkerIdByNodeId(nodeId);
    }

    @Override
    public List<ComputeNode> allNodes() {
        return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().backendAndComputeNodeStream()
                .collect(Collectors.toList());
    }

    @Override
    public ComputeNode nodeById(long nodeId) {
        return GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getBackendOrComputeNode(nodeId);
    }

    @Override
    public Collection<Long> knownWarehouseIds() {
        return GlobalStateMgr.getCurrentState().getWarehouseMgr().getAllWarehouseIds();
    }

    @Override
    public boolean warehouseExists(long warehouseId) {
        return GlobalStateMgr.getCurrentState().getWarehouseMgr().warehouseExists(warehouseId);
    }

    @Override
    public Map<String, List<String>> warehousesByResourceGroup() {
        ResourceGroupMgr resourceGroups = GlobalStateMgr.getCurrentState().getResourceGroupMgr();
        Map<String, List<String>> result = new TreeMap<>();
        // The id list is a copy, so it is safe to walk while other sessions create and drop resource groups.
        for (long id : resourceGroups.getResourceGroupIds()) {
            ResourceGroup group = resourceGroups.getResourceGroup(id);
            // Null when the group was dropped after the ids were listed.
            List<String> warehouses = group == null ? null : group.getWarehouses();
            if (warehouses != null && !warehouses.isEmpty()) {
                result.put(group.getName(), new ArrayList<>(warehouses));
            }
        }
        return result;
    }

    @Override
    public Optional<String> findWarehouseReference(long warehouseId) throws DdlException {
        GlobalStateMgr state = GlobalStateMgr.getCurrentState();
        Locker locker = new Locker();
        for (Database db : state.getLocalMetastore().getAllDbs()) {
            if (!locker.lockDatabaseAndCheckExist(db, LockType.READ)) {
                continue;
            }
            try {
                for (MaterializedView view : db.getMaterializedViews()) {
                    if (view.getWarehouseId() == warehouseId) {
                        return Optional.of("materialized view " + db.getFullName() + "." + view.getName());
                    }
                }
            } finally {
                locker.unLockDatabase(db.getId(), LockType.READ);
            }
        }
        try {
            // Excluding history keeps paused jobs, which can resume on the same warehouse.
            for (RoutineLoadJob job : state.getRoutineLoadMgr().getJob(null, null, false)) {
                if (job.getWarehouseId() == warehouseId) {
                    return Optional.of("Routine Load job " + job.getName() + " (ID " + job.getId() + ")");
                }
            }
        } catch (MetaNotFoundException e) {
            throw new DdlException("Cannot check Routine Load jobs for warehouse ID " + warehouseId, e);
        }
        return Optional.empty();
    }

    @Override
    public boolean runPeriodically(String name, long intervalMs, Runnable task) {
        if (!startedTasks.add(name)) {
            return false;
        }
        // A FrontendDaemon waits until the serving FE state is ready before every round, and it always sees the
        // serving state, never a checkpoint copy.
        new FrontendDaemon(name, intervalMs) {
            @Override
            protected void runAfterCatalogReady() {
                task.run();
            }
        }.start();
        return true;
    }

    @Override
    public boolean hasUserSession() {
        ConnectContext context = ConnectContext.get();
        return context != null && context.getCurrentUserIdentity() != null;
    }

    @Override
    public Object currentSession() {
        return ConnectContext.get();
    }

    @Override
    public void requireUsage(String warehouseName) {
        if (!hasUsage(warehouseName)) {
            throw usageDenied(ConnectContext.get(), warehouseName);
        }
    }

    @Override
    public boolean hasUsage(String warehouseName) {
        try {
            Authorizer.checkWarehouseAction(ConnectContext.get(), warehouseName, PrivilegeType.USAGE);
            return true;
        } catch (AccessDeniedException denied) {
            return false;
        }
    }

    @Override
    public UUID currentStatement() {
        ConnectContext context = ConnectContext.get();
        return context == null ? null : context.getQueryId();
    }

    @Override
    public boolean isStatementFinished() {
        ConnectContext context = ConnectContext.get();
        return context != null && !context.getState().isRunning();
    }

    /**
     * The error the FE raises for SET warehouse without the privilege: same code, same text. Built here, not
     * with AccessDeniedException.reportAccessDenied, because that method throws the error instead of returning
     * it.
     *
     * <p>Building it is not free of effects: ErrorReportException.report writes the error into the session on
     * the calling thread, and the session answers its statement with it even when a caller catches the
     * exception. Build it only for a statement that must fail.
     */
    private static ErrorReportException usageDenied(ConnectContext context, String warehouseName) {
        AuthorizationMgr authorization = GlobalStateMgr.getCurrentState().getAuthorizationMgr();
        List<String> activeRoles = authorization.getRoleNamesByRoleIds(context.getCurrentRoleIds());
        List<String> inactiveRoles =
                authorization.getInactivatedRoleNamesByUser(context.getCurrentUserIdentity(), activeRoles);
        return ErrorReportException.report(ErrorCode.ERR_ACCESS_DENIED, PrivilegeType.USAGE.name(),
                ObjectType.WAREHOUSE.name(), " " + warehouseName,
                activeRoles.isEmpty() ? "NONE" : activeRoles, inactiveRoles.isEmpty() ? "NONE" : inactiveRoles);
    }

    /**
     * A session that acquires its own compute resource always gets a {@code LazyComputeResource}: that is what
     * {@code ConnectContext} builds, and nothing else does. A resource set from outside, such as the stored one
     * of a load job, is of another type.
     */
    @Override
    public boolean isAcquiredBySession(ComputeResource resource) {
        ConnectContext context = ConnectContext.get();
        return context != null && resource instanceof LazyComputeResource
                && context.getCurrentComputeResourceNoAcquire() == resource;
    }

    @Override
    public void registerGauge(String name, String description, LongSupplier value) {
        MetricRepo.addMetric(new GaugeMetric<Long>(name, Metric.MetricUnit.NOUNIT, description) {
            @Override
            public Long getValue() {
                return value.getAsLong();
            }
        });
    }
}
