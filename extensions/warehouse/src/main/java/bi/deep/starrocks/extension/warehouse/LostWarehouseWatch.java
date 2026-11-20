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

import com.starrocks.system.ComputeNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Raises an alarm when warehouse metadata has been lost.
 *
 * <p>An FE without the extension jar starts normally and can be picked to write the checkpoint image. That image
 * has no warehouses in it, every FE receives it, and each FE then loses its warehouses at its next restart.
 * No FE reports the loss itself. What remains visible afterwards is the nodes: they still carry the id of a
 * warehouse that no longer exists. This class looks for such nodes once the FE has loaded its metadata and then
 * once a minute.
 *
 * <p>It reports in two places: an ERROR line in fe.log, repeated every ten minutes while the condition lasts, and
 * a gauge on the FE's metrics endpoint for alerting.
 *
 * <p>A warehouse that had no nodes leaves no trace, so its loss is not detected.
 *
 * <p>The repair named in the log line works because {@code ALTER WAREHOUSE} writes the whole warehouse to the
 * edit log even when nothing changes, and replay adds a warehouse an FE does not have.
 */
final class LostWarehouseWatch {
    static final String NAME = "warehouse-extension-lost-metadata-watch";

    /**
     * Shown on the metrics endpoint with the FE's prefix: {@code starrocks_fe_warehouse_extension_orphaned_nodes}.
     */
    static final String GAUGE = "warehouse_extension_orphaned_nodes";
    static final String GAUGE_DESCRIPTION = "nodes whose warehouse is not known to this FE; above 0 means"
            + " warehouse metadata was lost";

    static final long CHECK_INTERVAL_MS = 60_000L;
    static final long REPEAT_REPORT_MS = 600_000L;

    private static final Logger LOG = LogManager.getLogger(LostWarehouseWatch.class);

    private final FeEnvironment env;

    private boolean gaugeRegistered;
    private volatile long orphanCount;
    private String lastFinding = "";
    private long lastReportMs;

    LostWarehouseWatch(FeEnvironment env) {
        this.env = env;
    }

    /**
     * Starts the watch. The FE environment runs one watch per FE process, so calling this again does nothing.
     *
     * @return true when this call started it
     */
    static boolean install(FeEnvironment env) {
        LostWarehouseWatch watch = new LostWarehouseWatch(env);
        return env.runPeriodically(NAME, CHECK_INTERVAL_MS, watch::check);
    }

    long orphanCount() {
        return orphanCount;
    }

    /**
     * One round: finds the nodes without a known warehouse, updates the gauge and writes to the log when there
     * is something new to say.
     *
     * @return the text written to the log in this round, or an empty string
     */
    String check() {
        if (!gaugeRegistered) {
            // Here and not at install time: the FE sets up its metrics while it takes its role, which is after
            // the watch is installed and before its first round.
            env.registerGauge(GAUGE, GAUGE_DESCRIPTION, this::orphanCount);
            gaugeRegistered = true;
        }

        List<ComputeNode> orphans = confirmedOrphans();
        orphanCount = orphans.size();
        String finding = describe(orphans);
        String previous = lastFinding;
        lastFinding = finding;
        long now = env.currentTimeMillis();

        if (finding.isEmpty()) {
            if (previous.isEmpty()) {
                return "";
            }
            String message = "Every node belongs to a known warehouse again";
            LOG.info(message);
            return message;
        }
        if (finding.equals(previous) && now - lastReportMs < REPEAT_REPORT_MS) {
            return "";
        }
        lastReportMs = now;
        String message = orphans.size() + " node(s) belong to a warehouse this FE does not know: " + finding
                + ". Warehouse metadata is missing. The usual cause is an FE without the warehouse extension jar"
                + " that wrote a checkpoint image; a node added while its warehouse was being dropped looks the"
                + " same. Check that every FE has the jar. For each missing warehouse ID, check SHOW WAREHOUSES"
                + " on each FE. If an FE still lists that ID, do not restart it. A follower forwards ALTER"
                + " WAREHOUSE to the leader. If only a follower has the definition, keep quorum and elect that"
                + " running follower. Confirm with SHOW FRONTENDS that it is now leader. If only an observer has"
                + " the definition or the follower cannot be safely elected, keep it running and seek help"
                + " recovering its metadata. On a leader that lists the original ID, run ALTER WAREHOUSE <name>"
                + " SET ('replica_number' = '<its current value>'). Verify that every FE lists the recovered IDs"
                + " before running ALTER SYSTEM CREATE IMAGE. If no FE retains a warehouse's definition, drop its"
                + " orphaned nodes with ALTER SYSTEM DROP COMPUTE NODE and create that warehouse again";
        LOG.error(message);
        return message;
    }

    private List<ComputeNode> confirmedOrphans() {
        List<ComputeNode> candidates = findOrphans(env.allNodes(), env.knownWarehouseIds());
        List<ComputeNode> confirmed = new ArrayList<>(candidates.size());
        for (ComputeNode candidate : candidates) {
            long warehouseId = candidate.getWarehouseId();
            if (env.warehouseExists(warehouseId)) {
                continue;
            }
            // A normal drop removes the node before its warehouse. Check the node registration last.
            ComputeNode current = env.nodeById(candidate.getId());
            if (current != null && current.getWarehouseId() == warehouseId) {
                confirmed.add(current);
            }
        }
        return confirmed;
    }

    /**
     * @return the nodes whose warehouse id is not among {@code knownWarehouseIds}, ordered by node id
     */
    static List<ComputeNode> findOrphans(Collection<ComputeNode> nodes, Collection<Long> knownWarehouseIds) {
        Set<Long> known = new HashSet<>(knownWarehouseIds);
        List<ComputeNode> orphans = new ArrayList<>();
        for (ComputeNode node : nodes) {
            if (!known.contains(node.getWarehouseId())) {
                orphans.add(node);
            }
        }
        orphans.sort(Comparator.comparingLong(ComputeNode::getId));
        return orphans;
    }

    private static String describe(List<ComputeNode> orphans) {
        List<String> parts = new ArrayList<>(orphans.size());
        for (ComputeNode node : orphans) {
            parts.add(node.getHost() + ":" + node.getHeartbeatPort() + " (warehouse id " + node.getWarehouseId() + ")");
        }
        return String.join(", ", parts);
    }
}
