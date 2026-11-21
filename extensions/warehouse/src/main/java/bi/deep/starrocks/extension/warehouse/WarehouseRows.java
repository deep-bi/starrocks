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

import com.starrocks.common.util.TimeUtils;
import com.starrocks.system.ComputeNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongUnaryOperator;

/**
 * Builds the rows of {@code SHOW WAREHOUSES} and {@code SHOW NODES FROM WAREHOUSE} for a {@link UserWarehouse}.
 *
 * <p>The FE defines the columns of both statements and expects one string per column, in its order.
 */
final class WarehouseRows {
    static final String STATE_AVAILABLE = "AVAILABLE";
    static final String STATE_UNAVAILABLE = "UNAVAILABLE";

    /**
     * Columns of SHOW NODES FROM WAREHOUSE, in the FE's order.
     */
    static final List<String> NODE_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "WarehouseName", "CNGroupId", "WorkerGroupId", "NodeId", "WorkerId", "IP", "HeartbeatPort", "BePort",
            "HttpPort", "BrpcPort", "StarletPort", "LastStartTime", "LastUpdateMs", "Alive", "ErrMsg", "Version",
            "NumRunningQueries", "CpuCores", "MemUsedPct", "CpuUsedPct", "CNGroupName"));

    /**
     * Number of columns of SHOW WAREHOUSES.
     */
    static final int SUMMARY_COLUMN_COUNT = 14;

    private WarehouseRows() {
    }

    /**
     * One SHOW WAREHOUSES row. The warehouse is AVAILABLE when at least one of its nodes is alive.
     *
     * @param nodes the nodes registered in the warehouse, alive or not
     */
    static List<String> summary(UserWarehouse warehouse, List<ComputeNode> nodes) {
        boolean anyAlive = nodes.stream().anyMatch(ComputeNode::isAlive);
        List<String> row = new ArrayList<>(SUMMARY_COLUMN_COUNT);
        row.add(String.valueOf(warehouse.getId()));                       // Id
        row.add(warehouse.getName());                                     // Name
        row.add(anyAlive ? STATE_AVAILABLE : STATE_UNAVAILABLE);          // State
        row.add(String.valueOf(nodes.size()));                            // NodeCount
        // A warehouse is one worker group: one cluster, always started.
        row.add("1");                                                     // CurrentClusterCount
        row.add("1");                                                     // MaxClusterCount
        row.add("1");                                                     // StartedClusters
        // The extension does not track statements per warehouse.
        row.add("0");                                                     // RunningSql
        row.add("0");                                                     // QueuedSql
        row.add(TimeUtils.longToTimeString(warehouse.getCreateTimeMs())); // CreatedOn
        row.add("");                                                      // ResumedOn: there is no suspend
        row.add(TimeUtils.longToTimeString(warehouse.getUpdateTimeMs())); // UpdatedOn
        row.add(WarehouseOptions.describe(warehouse.getReplicaNumber())); // Property
        row.add(text(warehouse.getComment()));                            // Comment
        return row;
    }

    /**
     * The SHOW NODES rows of a warehouse, ordered by node id.
     *
     * @param workerIdOf resolves a node id to its StarMgr worker id
     */
    static List<List<String>> nodes(UserWarehouse warehouse, List<ComputeNode> nodes, LongUnaryOperator workerIdOf) {
        List<ComputeNode> ordered = new ArrayList<>(nodes);
        ordered.sort(Comparator.comparingLong(ComputeNode::getId));
        List<List<String>> rows = new ArrayList<>(ordered.size());
        for (ComputeNode node : ordered) {
            List<String> row = new ArrayList<>(NODE_COLUMNS.size());
            row.add(warehouse.getName());                                         // WarehouseName
            row.add("");                                                          // CNGroupId: no CN groups
            row.add(String.valueOf(warehouse.getWorkerGroupId()));                // WorkerGroupId
            row.add(String.valueOf(node.getId()));                                // NodeId
            row.add(String.valueOf(workerIdOf.applyAsLong(node.getId())));        // WorkerId
            row.add(text(node.getHost()));                                        // IP
            row.add(String.valueOf(node.getHeartbeatPort()));                     // HeartbeatPort
            row.add(String.valueOf(node.getBePort()));                            // BePort
            row.add(String.valueOf(node.getHttpPort()));                          // HttpPort
            row.add(String.valueOf(node.getBrpcPort()));                          // BrpcPort
            row.add(String.valueOf(node.getStarletPort()));                       // StarletPort
            row.add(TimeUtils.longToTimeString(node.getLastStartTime()));         // LastStartTime
            row.add(TimeUtils.longToTimeString(node.getLastUpdateMs()));          // LastUpdateMs
            row.add(String.valueOf(node.isAlive()));                              // Alive
            row.add(text(node.getHeartbeatErrMsg()));                             // ErrMsg
            row.add(text(node.getVersion()));                                     // Version
            row.add(String.valueOf(node.getNumRunningQueries()));                 // NumRunningQueries
            row.add(String.valueOf(node.getCpuCores()));                          // CpuCores
            row.add(percent(node.getMemUsedPct() * 100.0));                       // MemUsedPct
            row.add(percent(node.getCpuUsedPermille() / 10.0));                   // CpuUsedPct
            row.add("");                                                          // CNGroupName: no CN groups
            rows.add(row);
        }
        return rows;
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
