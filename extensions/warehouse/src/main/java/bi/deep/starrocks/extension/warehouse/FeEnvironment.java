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
import com.starrocks.persist.WALApplier;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.cngroup.ComputeResource;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Access to FE services and metadata used by the warehouse extension.
 *
 * <p>The warehouse logic reaches the FE only through this interface. {@link LiveFeEnvironment} is the one place
 * that touches the FE's global state, and tests replace it with an in-memory implementation.
 */
interface FeEnvironment {

    boolean isSharedData();

    /**
     * @return a new id from the FE's global id generator
     */
    long nextId();

    long currentTimeMillis();

    /**
     * Creates a StarMgr worker group.
     *
     * @return the id of the new group
     */
    long createWorkerGroup(int replicaNumber) throws DdlException;

    void updateWorkerGroup(long workerGroupId, int replicaNumber) throws DdlException;

    void deleteWorkerGroup(long workerGroupId) throws DdlException;

    /**
     * @return the replica number StarMgr holds for a worker group right now
     */
    int replicaNumberInStarMgr(long workerGroupId) throws DdlException;

    /**
     * @return true when this FE is the leader and has finished taking over, which includes replaying the whole
     *         edit log. Only then may it write to StarMgr outside a statement: a follower, or a leader that is
     *         still replaying, would act on stored values that are already out of date
     */
    boolean isActiveLeader();

    /**
     * @return the warehouse manager this FE serves from, never the copy of a checkpoint; null when that manager
     *         is not this extension's
     */
    MultiWarehouseManager servingManager();

    /**
     * Writes one entry to the edit log and, once it is durable, runs {@code afterWrite} with that entry.
     */
    void writeJournal(short operation, Object entry, WALApplier afterWrite);

    /**
     * @return the backends and compute nodes registered in the warehouse, alive or not
     */
    List<ComputeNode> nodesOf(long warehouseId);

    /**
     * @return the StarMgr worker id of a node, or -1 while the node has not registered with StarMgr
     */
    long workerIdOf(long nodeId);

    /**
     * @return every backend and compute node of the cluster
     */
    List<ComputeNode> allNodes();

    /** Returns the currently registered backend or compute node, or null if it has been removed. */
    ComputeNode nodeById(long nodeId);

    /**
     * @return the ids of the warehouses this FE knows, the default warehouse included
     */
    Collection<Long> knownWarehouseIds();

    boolean warehouseExists(long warehouseId);

    /**
     * A resource group can be limited to warehouses with its {@code warehouses} property. The FE stores the
     * names, not the ids.
     *
     * @return the warehouse names of every resource group that has the property set, by resource group name.
     *         Groups without the property are left out
     */
    Map<String, List<String>> warehousesByResourceGroup();

    /**
     * Returns a description identifying one materialized view or nonterminal Routine Load job using this warehouse ID.
     */
    Optional<String> findWarehouseReference(long warehouseId) throws DdlException;

    /**
     * Runs {@code task} once the FE has loaded its metadata, and then again every {@code intervalMs}.
     *
     * @return false when a task with this name is already running in this FE process; nothing is started then
     */
    boolean runPeriodically(String name, long intervalMs, Runnable task);

    /**
     * Publishes a number on the FE's metrics endpoint. {@code value} is read on every scrape.
     */
    void registerGauge(String name, String description, LongSupplier value);

    /**
     * @return true when the calling thread works for a logged-in user: a SQL session or an authenticated HTTP
     *         request. Background work of the FE has no user session
     */
    boolean hasUserSession();

    /**
     * A refresh of a materialized view runs in a session of its own, with the identity of the user who created
     * the view. Call it only when {@link #hasUserSession()} is true.
     *
     * @return true when the session on the calling thread refreshes a materialized view
     */
    boolean isMaterializedViewRefresh();

    /** Identity of the bound session. Call only after {@link #hasUserSession()} returns true. */
    Object currentSession();

    /**
     * A stock FE checks USAGE where a statement names a warehouse, and never for the refresh of a materialized
     * view. The gate leaves refreshes alone too, so a REVOKE from the view's creator does not stop them. Every
     * other session with a user is checked, a run of a task included.
     *
     * @return true when USAGE on the warehouse must be checked for the work on the calling thread
     */
    default boolean shouldCheckUsage(long warehouseId) {
        return warehouseId != WarehouseManager.DEFAULT_WAREHOUSE_ID
                && WarehouseExtensionSettings.current().isCheckUsage() && hasUserSession()
                && !isMaterializedViewRefresh();
    }

    /**
     * Checks that the user of the session on the calling thread holds USAGE on the warehouse. Call it only when
     * {@link #hasUserSession()} is true.
     *
     * @throws RuntimeException the FE's own access-denied error, the one SET warehouse raises, when the user
     *                          does not hold the privilege
     */
    void requireUsage(String warehouseName);

    /**
     * The same check as {@link #requireUsage}, as a question. Call it only when {@link #hasUserSession()} is
     * true.
     *
     * @return false when the user does not hold the privilege. Nothing is raised then, and the session on the
     *         calling thread is left as it is: the FE's access-denied error would be written into it
     */
    boolean hasUsage(String warehouseName);

    /**
     * @return what identifies the statement the session on the calling thread is running. The FE gives every
     *         statement a new one. Null on a thread without a session, and for a session that has none
     */
    UUID currentStatement();

    /**
     * @return true when the statement of the session on the calling thread already has its result or its error.
     *         What still runs then is work after the statement, such as building its profile
     */
    boolean isStatementFinished();

    /**
     * @return true when the session on the calling thread holds the lazy resource through which it acquires
     *         compute, including a retry's replacement. False for background work and resources handed to a
     *         session ready-made, as the FE does for the internal session of a load job
     */
    boolean isAcquiredBySession(ComputeResource resource);

    static FeEnvironment live() {
        return LiveFeEnvironment.INSTANCE;
    }
}
