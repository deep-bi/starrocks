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
import com.starrocks.system.ComputeNode;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Everything the warehouse classes need from the running FE: the run mode, id allocation, StarMgr worker groups,
 * the edit log, the node list and the resource groups.
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

    /**
     * @return the ids of the warehouses this FE knows, the default warehouse included
     */
    Collection<Long> knownWarehouseIds();

    /**
     * A resource group can be limited to warehouses with its {@code warehouses} property. The FE stores the
     * names, not the ids.
     *
     * @return the warehouse names of every resource group that has the property set, by resource group name.
     *         Groups without the property are left out
     */
    Map<String, List<String>> warehousesByResourceGroup();

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
     * Checks that the user of the session on the calling thread holds USAGE on the warehouse. Call it only when
     * {@link #hasUserSession()} is true.
     *
     * @throws RuntimeException the FE's own access-denied error, the one SET warehouse raises, when the user
     *                          does not hold the privilege
     */
    void requireUsage(String warehouseName);

    static FeEnvironment live() {
        return LiveFeEnvironment.INSTANCE;
    }
}
