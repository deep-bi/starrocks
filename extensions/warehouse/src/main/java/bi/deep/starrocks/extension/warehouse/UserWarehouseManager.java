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

import com.staros.util.LockCloseable;
import com.starrocks.common.DdlException;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The FE's {@code WarehouseManager} with {@code CREATE}, {@code ALTER} and {@code DROP WAREHOUSE} implemented.
 *
 * <p>The stock manager knows only {@code default_warehouse} and rejects every warehouse statement. This one keeps
 * {@link UserWarehouse} objects next to the default warehouse, in the maps it inherits, so everything the FE
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
public class UserWarehouseManager extends WarehouseManager {
    private static final Logger LOG = LogManager.getLogger(UserWarehouseManager.class);

    private final FeEnvironment env;
    private final Object statementLock = new Object();

    /**
     * The constructor the FE's extension context calls, once for the serving state and once per checkpoint.
     */
    @Inject
    public UserWarehouseManager() {
        this(FeEnvironment.live());
    }

    UserWarehouseManager(FeEnvironment env) {
        this(env, new WarehouseComputeResourceProvider());
    }

    /**
     * @param computeResources finds the nodes of a warehouse; the FE's own provider outside tests
     */
    UserWarehouseManager(FeEnvironment env, ComputeResourceProvider computeResources) {
        super(computeResources, new ArrayList<>());
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

    // ---------------------------------------------------------------- the gate

    /**
     * The first half of the gate: where a query, an INSERT or a Stream Load gets compute nodes it does not hold
     * yet, whatever statement, hint, user default or HTTP header named the warehouse. The FE itself checks USAGE
     * only for SET warehouse and the SET_VAR hint on a query. Checking it here as well means a user session
     * cannot skip it by naming the warehouse another way. A session that already holds its nodes does not come
     * back here; {@link #isResourceAvailable} covers that.
     *
     * <p>Requests on the default warehouse go straight to the FE's own code: every user may use it, as in a
     * stock FE. So does background work, which has no user session, on any warehouse.
     */
    @Override
    public ComputeResource acquireComputeResource(CRAcquireContext acquireContext) {
        long warehouseId = acquireContext.getWarehouseId();
        if (warehouseId != DEFAULT_WAREHOUSE_ID) {
            requireUsage(warehouseId);
        }
        return super.acquireComputeResource(acquireContext);
    }

    /**
     * The second half of the gate. A session acquires its compute resource once and keeps it; before every
     * later use it asks this method whether the resource can still be used. Without the check here, a
     * connection that is already open would go on running after its USAGE was revoked, until it reconnected.
     *
     * <p>The check covers only a resource the calling session acquired itself, which is one that passed
     * {@link #acquireComputeResource} with the same session. Every other caller gets the FE's own answer:
     * background work, and the internal session of a load job, which the FE hands a stored resource.
     *
     * @throws RuntimeException the FE's access-denied error, when the session's user no longer holds USAGE. The
     *                          stock method never throws. This one does, because "not available" would make the
     *                          session report a warehouse without nodes and ask the user to try again
     */
    @Override
    public boolean isResourceAvailable(ComputeResource computeResource) {
        long warehouseId = computeResource.getWarehouseId();
        if (warehouseId != DEFAULT_WAREHOUSE_ID && env.isAcquiredBySession(computeResource)) {
            requireUsage(warehouseId);
        }
        return super.isResourceAvailable(computeResource);
    }

    private void requireUsage(long warehouseId) {
        if (!WarehouseExtensionSettings.current().isCheckUsage() || !env.hasUserSession()) {
            return;
        }
        Warehouse warehouse = getWarehouseAllowNull(warehouseId);
        // An unknown id is left to the FE's own code, which reports it.
        if (warehouse != null) {
            env.requireUsage(warehouse.getName());
        }
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
                UserWarehouse warehouse = new UserWarehouse(env.nextId(), name, stmt.getComment(), workerGroupId,
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
            UserWarehouse current = requireUserWarehouse(name, "altered");
            WarehouseOptions options = WarehouseOptions.forAlter(stmt.getProperties(), current.getReplicaNumber());
            if (options.replicaNumber() == current.getReplicaNumber()) {
                // The statement that repairs. First StarMgr: a worker group that holds another number, after an
                // FE died in the middle of an earlier ALTER, is set to the stored one. Then the edit log: the
                // warehouse is written again, so FEs that lost it get it back on replay.
                alignWorkerGroup(current);
                env.writeJournal(OperationType.OP_ALTER_WAREHOUSE, current, entry -> install((Warehouse) entry));
                LOG.info("ALTER WAREHOUSE {}: the value is unchanged, wrote the warehouse to the edit log again", name);
                return;
            }

            // StarMgr first: if it refuses, nothing has been written and the statement fails.
            env.updateWorkerGroup(current.getWorkerGroupId(), options.replicaNumber());
            UserWarehouse changed = current.withReplicaNumber(options.replicaNumber(), env.currentTimeMillis());
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
            UserWarehouse warehouse = requireUserWarehouse(name, "dropped");

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
        List<UserWarehouse> warehouses = userWarehouses();
        if (warehouses.isEmpty()) {
            return;
        }
        SRMetaBlockWriter writer = imageWriter.getBlockWriter(SRMetaBlockID.WAREHOUSE_MGR, 1 + warehouses.size());
        writer.writeInt(warehouses.size());
        for (UserWarehouse warehouse : warehouses) {
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
        if (warehouse instanceof UserWarehouse) {
            ((UserWarehouse) warehouse).attach(env);
        }
        addWarehouse(warehouse);
    }

    private void uninstall(String name) {
        try (LockCloseable ignored = new LockCloseable(rwLock.writeLock())) {
            Warehouse removed = nameToWh.remove(name);
            if (removed == null) {
                return;
            }
            idToWh.remove(removed.getId());
            if (removed instanceof UserWarehouse) {
                ((UserWarehouse) removed).retire();
            }
        }
    }

    private List<UserWarehouse> userWarehouses() {
        try (LockCloseable ignored = new LockCloseable(rwLock.readLock())) {
            return idToWh.values().stream()
                    .filter(UserWarehouse.class::isInstance)
                    .map(UserWarehouse.class::cast)
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
    private UserWarehouse requireUserWarehouse(String name, String action) throws DdlException {
        Warehouse warehouse = getWarehouseAllowNull(name);
        if (warehouse == null) {
            throw WarehouseErrors.unknownWarehouse(name);
        }
        if (!(warehouse instanceof UserWarehouse)) {
            throw new DdlException("Warehouse " + name + " is built in and cannot be " + action);
        }
        return (UserWarehouse) warehouse;
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

    private void restoreReplicaNumberQuietly(UserWarehouse previous) {
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
     * <p>The statement lock is taken for one warehouse at a time, so a statement waits for one round trip to
     * StarMgr at most. A worker group that cannot be read or changed is left for the next round.
     *
     * @return the number of worker groups it corrected
     */
    int alignWorkerGroups() {
        int corrected = 0;
        List<String> unchecked = new ArrayList<>();
        for (UserWarehouse listed : userWarehouses()) {
            synchronized (statementLock) {
                // Looked up again under the lock: a statement may have altered or dropped it since the list
                // was made, and a stale copy would put an old number into StarMgr.
                Warehouse current = getWarehouseAllowNull(listed.getId());
                if (!(current instanceof UserWarehouse)) {
                    continue;
                }
                try {
                    if (alignWorkerGroup((UserWarehouse) current)) {
                        corrected++;
                    }
                } catch (DdlException | RuntimeException e) {
                    unchecked.add(current.getName() + " (" + e.getMessage() + ")");
                }
            }
        }
        if (!unchecked.isEmpty()) {
            LOG.warn("Could not check the worker group of {} warehouse(s) against StarMgr, will try again: {}",
                    unchecked.size(), String.join("; ", unchecked));
        }
        return corrected;
    }

    /**
     * Makes StarMgr hold the stored replica number of one warehouse. The caller holds the statement lock.
     *
     * @return true when StarMgr held another number and was corrected
     */
    private boolean alignWorkerGroup(UserWarehouse warehouse) throws DdlException {
        int inStarMgr = env.replicaNumberInStarMgr(warehouse.getWorkerGroupId());
        if (inStarMgr == warehouse.getReplicaNumber()) {
            return false;
        }
        env.updateWorkerGroup(warehouse.getWorkerGroupId(), warehouse.getReplicaNumber());
        LOG.warn("Worker group {} of warehouse {} had replica number {} in StarMgr, the stored value is {}."
                        + " StarMgr was set to the stored value",
                warehouse.getWorkerGroupId(), warehouse.getName(), inStarMgr, warehouse.getReplicaNumber());
        return true;
    }
}
