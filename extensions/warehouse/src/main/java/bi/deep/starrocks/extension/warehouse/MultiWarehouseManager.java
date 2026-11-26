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

import com.google.common.base.Strings;
import com.google.common.collect.MapMaker;
import com.staros.util.LockCloseable;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.extension.Inject;
import com.starrocks.persist.DropWarehouseLog;
import com.starrocks.persist.ImageWriter;
import com.starrocks.persist.OperationType;
import com.starrocks.persist.metablock.SRMetaBlockEOFException;
import com.starrocks.persist.metablock.SRMetaBlockException;
import com.starrocks.persist.metablock.SRMetaBlockID;
import com.starrocks.persist.metablock.SRMetaBlockReader;
import com.starrocks.persist.metablock.SRMetaBlockWriter;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.warehouse.AlterWarehouseStmt;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.sql.ast.warehouse.DropWarehouseStmt;
import com.starrocks.sql.ast.warehouse.ResumeWarehouseStmt;
import com.starrocks.sql.ast.warehouse.SuspendWarehouseStmt;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.CRAcquireContext;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.ComputeResourceProvider;
import com.starrocks.warehouse.cngroup.WarehouseComputeResourceProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * The FE's {@code WarehouseManager} with {@code CREATE}, {@code ALTER} and {@code DROP WAREHOUSE} implemented.
 *
 * <p>The stock manager knows only {@code default_warehouse} and rejects every warehouse statement. This one keeps
 * {@link UserDefinedWarehouse} objects next to the default warehouse, in the maps it inherits, so everything the FE
 * already does with a warehouse id or name (session variable, privileges, node assignment, scheduling) works for
 * them unchanged.
 *
 * <p>Rules every statement follows:
 * <ul>
 * <li>One statement at a time. The write lock of the inherited maps is held only for the map update, never while
 * waiting for StarMgr or the edit log, so queries are not blocked by a slow statement.</li>
 * <li>The warehouse maps change only in the edit-log callback, after the entry is durable. Followers and the
 * checkpoint thread apply the same entry through the {@code replay} methods.</li>
 * <li>Replay never calls StarMgr. Worker groups are created and deleted once, by the leader that ran the
 * statement.</li>
 * </ul>
 */
public class MultiWarehouseManager extends WarehouseManager {
    private static final Logger LOG = LogManager.getLogger(MultiWarehouseManager.class);
    private static final long ROUTING_WARNING_INTERVAL_MS = 600_000L;

    private final FeEnvironment env;
    private final RoutingWarning backgroundWarning = new RoutingWarning("lake_background_warehouse");
    private final RoutingWarning compactionWarning = new RoutingWarning("lake_compaction_warehouse");
    private final Object statementLock = new Object();
    // Each connection tracks the warehouse IDs approved for each query. Neither the session nor the query ID
    // is kept alive by the cache. A retry can replace the compute resource while the query is still running.
    private final ConcurrentMap<Object, Map<UUID, Set<Long>>> usageBySession = new MapMaker().weakKeys().makeMap();

    /**
     * The constructor the FE's extension context calls, once for the serving state and once per checkpoint.
     */
    @Inject
    public MultiWarehouseManager() {
        this(FeEnvironment.live());
    }

    MultiWarehouseManager(FeEnvironment env) {
        this(env, new WarehouseComputeResourceProvider());
    }

    /**
     * @param computeResources finds the nodes of a warehouse; the FE's own provider outside tests
     */
    MultiWarehouseManager(FeEnvironment env, ComputeResourceProvider computeResources) {
        super(new WarehouseUsageCheckingProvider(env, computeResources), new ArrayList<>());
        this.env = env;
    }

    // ---------------------------------------------------------------- start

    /**
     * The FE calls this on every start, when it takes its role. That makes it the place to start the periodic
     * work: the watch for lost warehouse metadata and the check of the worker groups against StarMgr. The call
     * also arrives on image load and in checkpoint copies of the FE state; each task is started only once per
     * FE process.
     */
    @Override
    public void initDefaultWarehouse() {
        super.initDefaultWarehouse();
        LostWarehouseWatch.install(env);
        StarMgrSync.install(env);
    }

    // ---------------------------------------------------------------- compute resource routing

    @Override
    public ComputeNode getComputeNodeAssignedToTablet(ComputeResource computeResource, long tabletId) {
        Long nodeId = getAliveComputeNodeId(computeResource, tabletId);
        // DROP can finish while StarMgr is looking up the tablet, even if the lookup returns a node ID.
        Warehouse warehouse = getWarehouse(computeResource.getWarehouseId());
        if (nodeId == null) {
            throw ErrorReportException.report(ErrorCode.ERR_NO_NODES_IN_WAREHOUSE, "name: " + warehouse.getName());
        }
        return env.nodeById(nodeId);
    }

    @Override
    public Warehouse getBackgroundWarehouse() {
        Warehouse warehouse = configuredWarehouse(Config.lake_background_warehouse, backgroundWarning);
        return warehouse == null ? super.getBackgroundWarehouse() : warehouse;
    }

    @Override
    public ComputeResource getBackgroundComputeResource() {
        return acquireMaintenanceResource(getBackgroundWarehouse());
    }

    @Override
    public ComputeResource getBackgroundComputeResource(long tableId) {
        return acquireMaintenanceResource(getBackgroundWarehouse(tableId));
    }

    /**
     * The FE compaction scheduler uses this count to stop scanning when every target is full. All tables
     * share one compaction target, either the configured warehouse or the default fallback.
     */
    @Override
    public int getAllWorkerGroupCount() {
        return 1;
    }

    @Override
    public ComputeResource getCompactionComputeResource(long tableId) {
        Warehouse warehouse = configuredWarehouse(Config.lake_compaction_warehouse, compactionWarning);
        if (warehouse != null && env.isSharedData()) {
            String problem;
            try {
                // Acquisition logs a WARN for every empty warehouse. The extension has one resource per warehouse.
                ComputeResource resource = computeResourceProvider.ofComputeResource(
                        warehouse.getId(), warehouse.getAnyWorkerGroupId());
                if (computeResourceProvider.isResourceAvailable(resource)) {
                    return resource;
                }
                problem = "has no available compute nodes";
            } catch (RuntimeException e) {
                problem = "could not provide compute (" + e + ")";
            }
            compactionWarning.report(warehouse.getName(), problem, env.currentTimeMillis());
        }
        return super.getCompactionComputeResource(tableId);
    }

    private ComputeResource acquireMaintenanceResource(Warehouse warehouse) {
        // Maintenance can run on a user's thread. The FE's long overload delegates back to the session USAGE check.
        return super.acquireComputeResource(CRAcquireContext.of(warehouse.getId()));
    }

    /** Returns null when the caller should use the FE's default for this kind of work. */
    private Warehouse configuredWarehouse(String name, RoutingWarning warning) {
        if (Strings.isNullOrEmpty(name) || DEFAULT_WAREHOUSE_NAME.equalsIgnoreCase(name)) {
            return null;
        }
        Warehouse warehouse = getWarehouseAllowNull(name);
        if (warehouse == null) {
            warning.report(name, "does not exist", env.currentTimeMillis());
        }
        return warehouse;
    }

    private static final class RoutingWarning {
        private final String setting;
        private String lastWarehouse;
        private long lastWarningMs;

        private RoutingWarning(String setting) {
            this.setting = setting;
        }

        private synchronized void report(String warehouse, String problem, long now) {
            if (warehouse.equals(lastWarehouse) && now >= lastWarningMs
                    && now - lastWarningMs < ROUTING_WARNING_INTERVAL_MS) {
                return;
            }
            lastWarehouse = warehouse;
            lastWarningMs = now;
            LOG.warn("Warehouse '{}' configured by {} {}. Using {}.", warehouse, setting, problem, DEFAULT_WAREHOUSE_NAME);
        }
    }

    // ---------------------------------------------------------------- the gate

    /**
     * The first half of the gate: where a query, an INSERT or a Stream Load without Merge Commit gets compute
     * nodes it does not hold yet, whatever statement, hint, user default or HTTP header named the warehouse.
     * The FE itself checks USAGE only for SET warehouse and the SET_VAR hint on a query. Checking it here as
     * well means a user session cannot skip it by naming the warehouse another way. A session calls
     * {@link #isResourceAvailable} when reusing its compute resource. An automatic retry can call this method
     * again to acquire a replacement. Both paths reuse a successful USAGE check for the same session, query ID,
     * and warehouse. The next statement checks USAGE again.
     *
     * <p>Requests on the default warehouse go straight to the FE's own code: every user may use it, as in a
     * stock FE. The FE also handles requests without a user session directly, and those of a materialized view
     * refresh: the refresh runs with the identity of the view's creator, but a stock FE never checks USAGE for
     * it. A run of a task is checked against the task's creator. Background and compaction select their
     * resources independently of the caller's USAGE, even on a user's thread.
     *
     * <p>A Stream Load with Merge Commit is the exception. The FE keeps one Merge Commit job per table and set
     * of load parameters, and the job holds the compute nodes. Only the request that creates the job comes
     * here, and it has a user session only when it arrived on the FE's HTTP port. A request that finds the job
     * does not come here, whichever user sends it. The request a compute node sends to the FE for each load
     * has no user session. Checking these needs a change in the FE.
     */
    @Override
    public ComputeResource acquireComputeResource(CRAcquireContext acquireContext) {
        long warehouseId = acquireContext.getWarehouseId();
        Warehouse warehouse = gated(warehouseId);
        if (warehouse != null) {
            ComputeResource held = acquireContext.getPrevComputeResource();
            if (held != null && held.getWarehouseId() == warehouseId && env.isAcquiredBySession(held)) {
                checkSessionUsage(warehouse, false);
            } else {
                env.requireUsage(warehouse.getName());
            }
        }
        return super.acquireComputeResource(acquireContext);
    }

    /**
     * The second half of the gate. A session acquires its compute resource once and keeps it; before every
     * later use it asks this method whether the resource can still be used. Without the check here, a
     * connection that is already open would go on running after its USAGE was revoked, until it reconnected.
     *
     * <p>The check covers resources acquired through the calling session, including a retry's replacement
     * resource before it is materialized. Background work, the refresh of a materialized view, and internal
     * load sessions that receive a stored resource use the FE's own availability check.
     *
     * <p>A statement is checked once per warehouse, including when a retry replaces the resource. The FE asks again in the
     * same statement, also after the statement has its result: it builds the profile of an INSERT after the
     * commit. A REVOKE that arrives in between must not turn a statement that has run into a failed one, so
     * these later questions get the FE's own answer. The REVOKE takes effect at the session's next statement.
     *
     * <p>A check that still finds no USAGE after the statement has finished raises nothing and answers "not
     * available". The session then drops the resource without an error and takes a new one, and its next
     * statement is refused here or in {@link #acquireComputeResource}.
     *
     * @throws RuntimeException the FE's access-denied error, when the session's user no longer holds USAGE and
     *                          the statement has not passed yet. The stock method never throws. This one does,
     *                          because "not available" would make a running statement report a warehouse
     *                          without nodes and ask the user to try again
     */
    @Override
    public boolean isResourceAvailable(ComputeResource computeResource) {
        long warehouseId = computeResource.getWarehouseId();
        if (warehouseId != DEFAULT_WAREHOUSE_ID && env.isAcquiredBySession(computeResource)) {
            Warehouse warehouse = gated(warehouseId);
            if (warehouse != null && !checkSessionUsage(warehouse, env.isStatementFinished())) {
                return false;
            }
        }
        return super.isResourceAvailable(computeResource);
    }

    /**
     * @return the warehouse the calling session needs USAGE on, or null when there is nothing to check: the
     *         default warehouse, the check switched off, a thread without a user session, the refresh of a
     *         materialized view, or an id the manager does not know, which is left to the FE's own code to report
     */
    private Warehouse gated(long warehouseId) {
        return env.shouldCheckUsage(warehouseId) ? getWarehouseAllowNull(warehouseId) : null;
    }

    /** A denial throws for running statements and returns false during post-statement profiling. */
    private boolean checkSessionUsage(Warehouse warehouse, boolean statementFinished) {
        UUID statement = env.currentStatement();
        if (statement == null) {
            return checkUsage(warehouse, statementFinished);
        }
        Map<UUID, Set<Long>> byStatement =
                usageBySession.computeIfAbsent(env.currentSession(), key -> new WeakHashMap<>());
        synchronized (byStatement) {
            Set<Long> approved = byStatement.computeIfAbsent(statement, key -> new HashSet<>());
            if (approved.contains(warehouse.getId())) {
                return true;
            }
            if (!checkUsage(warehouse, statementFinished)) {
                return false;
            }
            approved.add(warehouse.getId());
            return true;
        }
    }

    private boolean checkUsage(Warehouse warehouse, boolean statementFinished) {
        if (statementFinished) {
            return env.hasUsage(warehouse.getName());
        }
        env.requireUsage(warehouse.getName());
        return true;
    }

    // ---------------------------------------------------------------- statements

    @Override
    public void createWarehouse(CreateWarehouseStmt stmt) throws DdlException {
        requireSharedData();
        String name = stmt.getWarehouseName();
        synchronized (statementLock) {
            if (warehouseExists(name)) {
                if (stmt.isSetIfNotExists()) {
                    LOG.info("CREATE WAREHOUSE IF NOT EXISTS {}: it exists, nothing to do", name);
                    return;
                }
                throw WarehouseErrors.warehouseExists(name);
            }
            // Checked after the existence test. The Kubernetes operator runs CREATE WAREHOUSE IF NOT EXISTS on
            // every compute node start, and that must stay a no-op when creation is switched off.
            requireCreationEnabled();
            rejectNameDifferingOnlyByCase(name);
            WarehouseOptions options = WarehouseOptions.forCreate(stmt.getProperties());

            long workerGroupId = env.createWorkerGroup(options.replicaNumber());
            try {
                long now = env.currentTimeMillis();
                UserDefinedWarehouse warehouse = new UserDefinedWarehouse(env.nextId(), name, stmt.getComment(), workerGroupId,
                        options.replicaNumber(), now, now);
                env.writeJournal(OperationType.OP_CREATE_WAREHOUSE, warehouse, entry -> install((Warehouse) entry));
                LOG.info("Created {}", warehouse);
            } catch (RuntimeException | Error e) {
                // Nothing points at the group yet, so it would be unreachable.
                deleteWorkerGroupQuietly(workerGroupId, "creating warehouse " + name + " failed");
                throw e;
            }
        }
    }

    @Override
    public void alterWarehouse(AlterWarehouseStmt stmt) throws DdlException {
        requireSharedData();
        String name = stmt.getWarehouseName();
        synchronized (statementLock) {
            UserDefinedWarehouse current = requireUserDefinedWarehouse(name, "altered");
            WarehouseOptions options = WarehouseOptions.forAlter(stmt.getProperties(), current.getReplicaNumber());
            if (options.replicaNumber() == current.getReplicaNumber()) {
                // Followers can recover the unchanged definition even if StarMgr reconciliation fails.
                env.writeJournal(OperationType.OP_ALTER_WAREHOUSE, current, entry -> install((Warehouse) entry));
                LOG.info("ALTER WAREHOUSE {}: the value is unchanged, wrote the warehouse to the edit log again", name);
                try {
                    alignWorkerGroup(current);
                } catch (DdlException | RuntimeException e) {
                    throw new DdlException("The definition of warehouse '" + name + "' was written to the edit log, "
                            + "but StarMgr reconciliation failed for worker group " + current.getWorkerGroupId(), e);
                }
                return;
            }

            // StarMgr first: if it refuses, nothing has been written and the statement fails.
            env.updateWorkerGroup(current.getWorkerGroupId(), options.replicaNumber());
            UserDefinedWarehouse changed = current.withReplicaNumber(options.replicaNumber(), env.currentTimeMillis());
            try {
                env.writeJournal(OperationType.OP_ALTER_WAREHOUSE, changed, entry -> install((Warehouse) entry));
            } catch (RuntimeException | Error e) {
                restoreReplicaNumberQuietly(current);
                throw e;
            }
            LOG.info("Altered {}", changed);
        }
    }

    @Override
    public void dropWarehouse(DropWarehouseStmt stmt) throws DdlException {
        requireSharedData();
        String name = stmt.getWarehouseName();
        synchronized (statementLock) {
            if (!warehouseExists(name)) {
                if (stmt.isSetIfExists()) {
                    LOG.info("DROP WAREHOUSE IF EXISTS {}: it does not exist, nothing to do", name);
                    return;
                }
                throw WarehouseErrors.unknownWarehouse(name);
            }
            UserDefinedWarehouse warehouse = requireUserDefinedWarehouse(name, "dropped");

            // Retire first, then check. A node that starts joining from here on is refused.
            warehouse.retire();
            try {
                List<ComputeNode> nodes = env.nodesOf(warehouse.getId());
                if (!nodes.isEmpty()) {
                    throw new DdlException("Cannot drop warehouse " + name + ": " + nodes.size()
                            + " node(s) are registered in it (" + describe(nodes) + "). Remove each one with"
                            + " ALTER SYSTEM DROP COMPUTE NODE '<host>:<heartbeat port>' FROM WAREHOUSE " + name
                            + ", then drop the warehouse");
                }
                // A resource group keeps the name of a warehouse that is gone. It then matches no session, with
                // no error anywhere, and starts to apply again to whatever warehouse is later created under
                // that name.
                List<String> resourceGroups = resourceGroupsNaming(name);
                if (!resourceGroups.isEmpty()) {
                    throw new DdlException("Cannot drop warehouse " + name + ": resource group(s) "
                            + String.join(", ", resourceGroups) + " name it in their 'warehouses' property."
                            + " Change the property with ALTER RESOURCE GROUP <group> WITH ('warehouses' ="
                            + " '<warehouses to keep>'), or drop the resource group, then drop the warehouse");
                }
                Optional<String> reference = env.findWarehouseReference(warehouse.getId());
                if (reference.isPresent()) {
                    throw new DdlException("Cannot drop warehouse " + name + " because " + reference.get()
                            + " still references warehouse ID " + warehouse.getId());
                }
                env.writeJournal(OperationType.OP_DROP_WAREHOUSE, new DropWarehouseLog(name),
                        entry -> uninstall(((DropWarehouseLog) entry).getWarehouseName()));
            } catch (DdlException | RuntimeException | Error e) {
                warehouse.reinstate();
                throw e;
            }

            // After the edit log, not before: a crash in between leaves an unused group in StarMgr, never a
            // warehouse that points at a deleted group.
            deleteWorkerGroupQuietly(warehouse.getWorkerGroupId(), "warehouse " + name + " was dropped");
            LOG.info("Dropped {}", warehouse);
        }
    }

    @Override
    public void suspendWarehouse(SuspendWarehouseStmt stmt) throws DdlException {
        throw new DdlException("SUSPEND WAREHOUSE is not supported by the warehouse extension");
    }

    @Override
    public void resumeWarehouse(ResumeWarehouseStmt stmt) throws DdlException {
        throw new DdlException("RESUME WAREHOUSE is not supported by the warehouse extension");
    }

    @Override
    public Set<String> getAllWarehouseNames() {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            return new HashSet<>(nameToWh.keySet());
        }
    }

    // ---------------------------------------------------------------- replay

    @Override
    public void replayCreateWarehouse(Warehouse warehouse) {
        install(warehouse);
    }

    @Override
    public void replayAlterWarehouse(Warehouse warehouse) {
        install(warehouse);
    }

    @Override
    public void replayDropWarehouse(DropWarehouseLog log) {
        uninstall(log.getWarehouseName());
    }

    // ---------------------------------------------------------------- image

    /**
     * Writes the user warehouses to the image. With none, it writes nothing, like the stock manager. The image
     * is then the same as a stock FE's, so removing the jar once every warehouse has been dropped loses nothing.
     */
    @Override
    public void save(ImageWriter imageWriter) throws IOException, SRMetaBlockException {
        List<UserDefinedWarehouse> warehouses = userDefinedWarehouses();
        if (warehouses.isEmpty()) {
            return;
        }
        SRMetaBlockWriter writer = imageWriter.getBlockWriter(SRMetaBlockID.WAREHOUSE_MGR, 1 + warehouses.size());
        writer.writeInt(warehouses.size());
        for (UserDefinedWarehouse warehouse : warehouses) {
            writer.writeJson(warehouse);
        }
        writer.close();
    }

    @Override
    public void load(SRMetaBlockReader reader) throws SRMetaBlockEOFException, IOException, SRMetaBlockException {
        // The stock load() does only this. Code that runs right after the image is loaded expects the default
        // warehouse to be there.
        initDefaultWarehouse();
        reader.readCollection(Warehouse.class, this::install);
    }

    // ---------------------------------------------------------------- internals

    /**
     * Adds a warehouse, or replaces the one with the same name. Called when an edit-log entry has become
     * durable, on replay and on image load.
     */
    private void install(Warehouse warehouse) {
        if (warehouse instanceof UserDefinedWarehouse) {
            ((UserDefinedWarehouse) warehouse).attach(env);
        }
        try (LockCloseable ignored = new LockCloseable(rwLock.writeLock())) {
            Warehouse previous = nameToWh.put(warehouse.getName(), warehouse);
            if (previous != null && previous.getId() != warehouse.getId()) {
                removeWarehouseId(previous);
            }
            idToWh.put(warehouse.getId(), warehouse);
        }
    }

    private void uninstall(String name) {
        try (LockCloseable ignored = new LockCloseable(rwLock.writeLock())) {
            Warehouse removed = nameToWh.remove(name);
            if (removed != null) {
                removeWarehouseId(removed);
            }
        }
    }

    // Called under the write lock after the warehouse has lost its name mapping.
    private void removeWarehouseId(Warehouse warehouse) {
        idToWh.remove(warehouse.getId());
        if (warehouse instanceof UserDefinedWarehouse) {
            ((UserDefinedWarehouse) warehouse).retire();
        }
    }

    private List<UserDefinedWarehouse> userDefinedWarehouses() {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            return idToWh.values().stream()
                    .filter(UserDefinedWarehouse.class::isInstance)
                    .map(UserDefinedWarehouse.class::cast)
                    .sorted(Comparator.comparingLong(Warehouse::getId))
                    .collect(Collectors.toList());
        }
    }

    private void requireSharedData() throws DdlException {
        if (!env.isSharedData()) {
            throw new DdlException("Warehouses need shared-data mode (run_mode = shared_data)");
        }
    }

    private static void requireCreationEnabled() throws DdlException {
        if (!WarehouseExtensionSettings.current().isEnabled()) {
            throw new DdlException("Creating warehouses is disabled. Set " + WarehouseExtensionSettings.KEY_ENABLED
                    + " = true in fe.conf on every FE and restart them");
        }
    }

    /**
     * The FE compares warehouse names without regard to case in some places, such as the SET_VAR hint against
     * the session's warehouse, and exactly in others, such as the lookup by name. Two names that differ only by
     * case would be told apart in one statement and mixed up in the next.
     */
    private void rejectNameDifferingOnlyByCase(String name) throws DdlException {
        for (String existing : getAllWarehouseNames()) {
            if (existing.equalsIgnoreCase(name)) {
                throw new DdlException("Warehouse name '" + name + "' differs only by case from the existing"
                        + " warehouse '" + existing + "'");
            }
        }
    }

    /**
     * @param action "altered" or "dropped", for the error message
     */
    private UserDefinedWarehouse requireUserDefinedWarehouse(String name, String action) throws DdlException {
        Warehouse warehouse = getWarehouseAllowNull(name);
        if (warehouse == null) {
            throw WarehouseErrors.unknownWarehouse(name);
        }
        if (!(warehouse instanceof UserDefinedWarehouse)) {
            throw new DdlException("Warehouse " + name + " is built in and cannot be " + action);
        }
        return (UserDefinedWarehouse) warehouse;
    }

    /**
     * The FE compares the warehouse names of a resource group exactly, so this does too.
     *
     * @return the resource groups whose {@code warehouses} property names the warehouse, in alphabetical order
     */
    private List<String> resourceGroupsNaming(String warehouseName) {
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : env.warehousesByResourceGroup().entrySet()) {
            if (group.getValue().contains(warehouseName)) {
                groups.add(group.getKey());
            }
        }
        groups.sort(Comparator.naturalOrder());
        return groups;
    }

    private static String describe(List<ComputeNode> nodes) {
        List<String> addresses = new ArrayList<>(nodes.size());
        for (ComputeNode node : nodes) {
            addresses.add(node.getHost() + ":" + node.getHeartbeatPort());
        }
        addresses.sort(Comparator.naturalOrder());
        return String.join(", ", addresses);
    }

    private void deleteWorkerGroupQuietly(long workerGroupId, String reason) {
        try {
            env.deleteWorkerGroup(workerGroupId);
        } catch (DdlException | RuntimeException e) {
            LOG.warn("Worker group {} is left behind in StarMgr ({}): {}", workerGroupId, reason, e.getMessage());
        }
    }

    private void restoreReplicaNumberQuietly(UserDefinedWarehouse previous) {
        try {
            env.updateWorkerGroup(previous.getWorkerGroupId(), previous.getReplicaNumber());
        } catch (DdlException | RuntimeException e) {
            LOG.warn("Worker group {} of warehouse {} may not match the stored replica number {} until the leader's"
                            + " next check of StarMgr: {}",
                    previous.getWorkerGroupId(), previous.getName(), previous.getReplicaNumber(), e.getMessage());
        }
    }

    /**
     * Makes StarMgr hold the stored replica number of every warehouse. {@link StarMgrSync} calls this on the
     * leader once a minute.
     *
     * <p>The periodic check reads StarMgr without the statement lock. It takes the lock only to recheck the
     * warehouse and correct a mismatch. A worker group that cannot be read or changed is left for the next round.
     *
     * @return the number of worker groups it corrected
     */
    int alignWorkerGroups() {
        int corrected = 0;
        List<String> unchecked = new ArrayList<>();
        for (UserDefinedWarehouse listed : userDefinedWarehouses()) {
            Warehouse current = getWarehouseAllowNull(listed.getId());
            if (!(current instanceof UserDefinedWarehouse)) {
                continue;
            }
            try {
                if (alignWorkerGroup((UserDefinedWarehouse) current)) {
                    corrected++;
                }
            } catch (DdlException | RuntimeException e) {
                unchecked.add(current.getName() + " (" + e.getMessage() + ")");
            }
        }
        if (!unchecked.isEmpty()) {
            LOG.warn("Could not check the worker group of {} warehouse(s) against StarMgr, will try again: {}",
                    unchecked.size(), String.join("; ", unchecked));
        }
        return corrected;
    }

    /**
     * Corrects a worker group if its warehouse definition is still current after the StarMgr read.
     *
     * @return true when StarMgr held another number and was corrected
     */
    private boolean alignWorkerGroup(UserDefinedWarehouse warehouse) throws DdlException {
        int inStarMgr = env.replicaNumberInStarMgr(warehouse.getWorkerGroupId());
        if (inStarMgr == warehouse.getReplicaNumber()) {
            return false;
        }
        synchronized (statementLock) {
            // Changing the replica number installs a new definition with the same warehouse and worker-group IDs.
            if (getWarehouseAllowNull(warehouse.getId()) != warehouse) {
                return false;
            }
            env.updateWorkerGroup(warehouse.getWorkerGroupId(), warehouse.getReplicaNumber());
            LOG.warn("Worker group {} of warehouse {} had replica number {} in StarMgr, the stored value is {}."
                            + " StarMgr was set to the stored value",
                    warehouse.getWorkerGroupId(), warehouse.getName(), inStarMgr, warehouse.getReplicaNumber());
            return true;
        }
    }
}
