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

import com.starrocks.common.DdlException;
import com.starrocks.persist.DropWarehouseLog;
import com.starrocks.persist.OperationType;
import com.starrocks.persist.WALApplier;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.ComputeResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * An FE that lives in memory: StarMgr worker groups are a map, the edit log is a list, nodes are a list.
 *
 * <p>The edit log keeps every entry as the JSON the real FE would write, produced by the FE's own Gson, so
 * {@link #replayInto} hands a follower what a real follower gets: a fresh object read back from JSON.
 */
class FakeFeEnvironment implements FeEnvironment {

    static final class JournalEntry {
        final short operation;
        final String json;

        JournalEntry(short operation, String json) {
            this.operation = operation;
            this.json = json;
        }
    }

    boolean sharedData = true;
    long nextId = 10001L;
    long clock = 1_700_000_000_000L;
    long nextWorkerGroupId = 501L;

    /** Worker group id to replica number, for every group that exists in the fake StarMgr. */
    final Map<Long, Integer> workerGroups = new LinkedHashMap<>();
    final List<Long> deletedWorkerGroups = new ArrayList<>();
    final List<JournalEntry> journal = new ArrayList<>();
    final List<ComputeNode> nodes = new ArrayList<>();
    final Map<Long, Long> workerIds = new HashMap<>();

    /** Periodic tasks by name. A test runs one by calling it; nothing runs on its own. */
    final Map<String, Runnable> periodicTasks = new LinkedHashMap<>();
    final Map<String, Long> periodicIntervals = new HashMap<>();
    final Map<String, LongSupplier> gauges = new LinkedHashMap<>();
    /** The warehouse ids the serving FE state knows. A test usually points this at its manager. */
    Supplier<Collection<Long>> knownWarehouseIds = () -> Collections.singletonList(0L);

    /** Resource group name to the warehouse names in its 'warehouses' property. */
    final Map<String, List<String>> resourceGroups = new LinkedHashMap<>();

    /** The user whose session is bound to the calling thread, or null for background work. */
    String sessionUser;
    /** Granted privileges, each as "user on warehouse". */
    final java.util.Set<String> usageGrants = new java.util.HashSet<>();
    /** Every privilege check made, each as "user on warehouse". */
    final List<String> usageChecks = new ArrayList<>();
    /** The compute resource the session on the calling thread acquired itself and still holds, or null. */
    ComputeResource sessionResource;
    /** What identifies the statement the session is running, or null for a session without one. */
    Object statement = "statement 1";
    /** True once the statement has its result or its error. */
    boolean statementFinished;
    private int statements = 1;

    /** The session starts its next statement. */
    void nextStatement() {
        statement = "statement " + (++statements);
        statementFinished = false;
    }

    /** Number of calls made to the fake StarMgr, to prove replay makes none. */
    int starMgrCalls;

    /** Whether this FE is the leader and has finished taking over. */
    boolean activeLeader = true;
    /** The manager the FE serves from. Null stands for an FE whose manager is not the extension's. */
    UserWarehouseManager servingManager;

    DdlException failCreateWorkerGroup;
    DdlException failUpdateWorkerGroup;
    /** Thrown when StarMgr is asked for a worker group: a DdlException or a RuntimeException. */
    Exception failReadWorkerGroup;
    /** Thrown by deleteWorkerGroup: a DdlException or a RuntimeException. */
    Exception failDeleteWorkerGroup;
    RuntimeException failJournal;
    /** When set, only the update that tries to set this replica number succeeds; used to fail a rollback. */
    Integer onlyAllowedReplicaNumber;

    static {
        WarehouseSubtypeRegistration.ensureRegistered();
    }

    @Override
    public boolean isSharedData() {
        return sharedData;
    }

    @Override
    public long nextId() {
        return nextId++;
    }

    @Override
    public long currentTimeMillis() {
        return clock;
    }

    @Override
    public long createWorkerGroup(int replicaNumber) throws DdlException {
        starMgrCalls++;
        if (failCreateWorkerGroup != null) {
            throw failCreateWorkerGroup;
        }
        long id = nextWorkerGroupId++;
        workerGroups.put(id, replicaNumber);
        return id;
    }

    @Override
    public void updateWorkerGroup(long workerGroupId, int replicaNumber) throws DdlException {
        starMgrCalls++;
        if (failUpdateWorkerGroup != null) {
            throw failUpdateWorkerGroup;
        }
        if (onlyAllowedReplicaNumber != null && onlyAllowedReplicaNumber != replicaNumber) {
            throw new IllegalStateException("StarMgr is gone");
        }
        workerGroups.put(workerGroupId, replicaNumber);
    }

    @Override
    public int replicaNumberInStarMgr(long workerGroupId) throws DdlException {
        starMgrCalls++;
        if (failReadWorkerGroup instanceof DdlException) {
            throw (DdlException) failReadWorkerGroup;
        }
        if (failReadWorkerGroup instanceof RuntimeException) {
            throw (RuntimeException) failReadWorkerGroup;
        }
        Integer replicaNumber = workerGroups.get(workerGroupId);
        if (replicaNumber == null) {
            throw new DdlException("Failed to get worker group info (id=" + workerGroupId + ")");
        }
        return replicaNumber;
    }

    @Override
    public boolean isActiveLeader() {
        return activeLeader;
    }

    @Override
    public UserWarehouseManager servingManager() {
        return servingManager;
    }

    @Override
    public void deleteWorkerGroup(long workerGroupId) throws DdlException {
        starMgrCalls++;
        if (failDeleteWorkerGroup instanceof DdlException) {
            throw (DdlException) failDeleteWorkerGroup;
        }
        if (failDeleteWorkerGroup instanceof RuntimeException) {
            throw (RuntimeException) failDeleteWorkerGroup;
        }
        workerGroups.remove(workerGroupId);
        deletedWorkerGroups.add(workerGroupId);
    }

    @Override
    public void writeJournal(short operation, Object entry, WALApplier afterWrite) {
        if (failJournal != null) {
            throw failJournal;
        }
        journal.add(new JournalEntry(operation, GsonUtils.GSON.toJson(entry)));
        afterWrite.apply(entry);
    }

    @Override
    public List<ComputeNode> nodesOf(long warehouseId) {
        return nodes.stream().filter(node -> node.getWarehouseId() == warehouseId).collect(Collectors.toList());
    }

    @Override
    public long workerIdOf(long nodeId) {
        return workerIds.getOrDefault(nodeId, -1L);
    }

    @Override
    public List<ComputeNode> allNodes() {
        return new ArrayList<>(nodes);
    }

    @Override
    public Collection<Long> knownWarehouseIds() {
        return knownWarehouseIds.get();
    }

    @Override
    public Map<String, List<String>> warehousesByResourceGroup() {
        return resourceGroups;
    }

    @Override
    public boolean runPeriodically(String name, long intervalMs, Runnable task) {
        if (periodicTasks.containsKey(name)) {
            return false;
        }
        periodicTasks.put(name, task);
        periodicIntervals.put(name, intervalMs);
        return true;
    }

    @Override
    public void registerGauge(String name, String description, LongSupplier value) {
        gauges.put(name, value);
    }

    @Override
    public boolean hasUserSession() {
        return sessionUser != null;
    }

    @Override
    public void requireUsage(String warehouseName) {
        if (!hasUsage(warehouseName)) {
            throw new AccessDenied(sessionUser, warehouseName);
        }
    }

    @Override
    public boolean hasUsage(String warehouseName) {
        if (sessionUser == null) {
            throw new AssertionError("USAGE checked without a user session");
        }
        usageChecks.add(sessionUser + " on " + warehouseName);
        return usageGrants.contains(sessionUser + " on " + warehouseName);
    }

    @Override
    public Object currentStatement() {
        return statement;
    }

    @Override
    public boolean isStatementFinished() {
        return statementFinished;
    }

    @Override
    public boolean isAcquiredBySession(ComputeResource resource) {
        return sessionResource == resource;
    }

    /** Stands in for the FE's access-denied error. */
    static final class AccessDenied extends RuntimeException {
        AccessDenied(String user, String warehouseName) {
            super("Access denied; " + user + " needs USAGE on WAREHOUSE " + warehouseName);
        }
    }

    /**
     * Adds a node the way ALTER SYSTEM ADD COMPUTE NODE ... INTO WAREHOUSE does: the warehouse stamps its ids on
     * the node, then the node becomes visible in the cluster.
     */
    ComputeNode addNode(long nodeId, String host, Warehouse warehouse, boolean alive) throws DdlException {
        ComputeNode node = new ComputeNode(nodeId, host, 9050);
        warehouse.addNodeToCNGroup(node, "");
        node.setAlive(alive);
        nodes.add(node);
        return node;
    }

    /**
     * Replays the whole edit log into another manager, as a follower FE or the checkpoint thread would.
     */
    void replayInto(UserWarehouseManager follower) {
        for (JournalEntry entry : journal) {
            if (entry.operation == OperationType.OP_CREATE_WAREHOUSE) {
                follower.replayCreateWarehouse(GsonUtils.GSON.fromJson(entry.json, Warehouse.class));
            } else if (entry.operation == OperationType.OP_ALTER_WAREHOUSE) {
                follower.replayAlterWarehouse(GsonUtils.GSON.fromJson(entry.json, Warehouse.class));
            } else if (entry.operation == OperationType.OP_DROP_WAREHOUSE) {
                follower.replayDropWarehouse(GsonUtils.GSON.fromJson(entry.json, DropWarehouseLog.class));
            } else {
                throw new IllegalStateException("Unexpected operation " + entry.operation);
            }
        }
    }

    List<Short> journaledOperations() {
        return journal.stream().map(entry -> entry.operation).collect(Collectors.toList());
    }
}
