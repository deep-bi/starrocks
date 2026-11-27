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

import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.system.ComputeNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class LostWarehouseWatchTest {
    private FakeFeEnvironment fe;
    private LostWarehouseWatch watch;

    @BeforeEach
    public void before() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false));
        fe = new FakeFeEnvironment();
        watch = new LostWarehouseWatch(fe);
    }

    @AfterEach
    public void after() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    private ComputeNode node(long id, String host, long warehouseId) {
        ComputeNode node = new ComputeNode(id, host, 9050);
        node.setWarehouseId(warehouseId);
        fe.nodes.add(node);
        return node;
    }

    private ComputeNode node(long id, String host, long warehouseId, long workerGroupId) {
        ComputeNode node = node(id, host, warehouseId);
        node.setWorkerGroupId(workerGroupId);
        return node;
    }

    private static List<Long> ids(List<ComputeNode> nodes) {
        return nodes.stream().map(ComputeNode::getId).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ finding orphans

    @Test
    public void aNodeIsAnOrphanWhenItsWarehouseIsUnknown() {
        ComputeNode inDefault = new ComputeNode(3L, "cn-c", 9050);
        inDefault.setWarehouseId(0L);
        ComputeNode inResearch = new ComputeNode(2L, "cn-b", 9050);
        inResearch.setWarehouseId(10001L);
        ComputeNode lostSecond = new ComputeNode(9L, "cn-z", 9050);
        lostSecond.setWarehouseId(10002L);
        ComputeNode lostFirst = new ComputeNode(1L, "cn-a", 9050);
        lostFirst.setWarehouseId(10002L);

        List<ComputeNode> orphans = LostWarehouseWatch.findOrphans(
                Arrays.asList(inDefault, inResearch, lostSecond, lostFirst), Arrays.asList(0L, 10001L));

        Assertions.assertEquals(Arrays.asList(1L, 9L), ids(orphans), "ordered by node id");
        Assertions.assertTrue(LostWarehouseWatch.findOrphans(Collections.emptyList(), Arrays.asList(0L)).isEmpty());
    }

    // ------------------------------------------------------------------ one round

    @Test
    public void aHealthyClusterIsSilent() {
        node(1L, "cn-a", 0L);

        Assertions.assertEquals("", watch.check());
        Assertions.assertEquals("", watch.check());

        Assertions.assertEquals(0L, watch.orphanCount());
    }

    @Test
    public void removingANodeAndItsWarehouseBetweenSnapshotsDoesNotRaiseAnAlarm() {
        ComputeNode retiring = node(1L, "cn-research", 10001L);
        node(2L, "cn-default", 0L);
        fe.knownWarehouseIds = () -> Arrays.asList(0L, retiring.getWarehouseId());
        Assertions.assertEquals("", watch.check());

        // The watch has already copied the nodes when it asks for the warehouse IDs.
        fe.knownWarehouseIds = () -> {
            fe.nodes.remove(retiring);
            return Collections.singletonList(0L);
        };
        String report = watch.check();

        Assertions.assertFalse(fe.nodes.contains(retiring), "the node was removed during this round");
        Assertions.assertAll(
                () -> Assertions.assertEquals("", report, "normal removal must not produce a metadata-loss report"),
                () -> Assertions.assertEquals(0L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong(),
                        "the published orphan count must stay zero in the same round"));
    }

    @Test
    public void onlyNodesStillRegisteredToAMissingWarehouseAreReported() {
        node(1L, "cn-restored", 10001L);
        ComputeNode reassigned = node(2L, "cn-moved", 10002L);
        node(3L, "cn-orphan", 10003L);
        fe.knownWarehouseIds = () -> {
            // All three warehouses were missing in the snapshot. One recovers and another node moves.
            fe.knownWarehouseIds = () -> Arrays.asList(0L, 10001L);
            fe.nodes.remove(reassigned);
            node(reassigned.getId(), reassigned.getHost(), 0L);
            return Collections.singletonList(0L);
        };

        String report = watch.check();

        Assertions.assertTrue(report.startsWith("Warehouse metadata is missing on this FE: 1 node(s) belong to 1 unknown"
                + " warehouse(s). Warehouse id 10003, worker group 0: node 3 at cn-orphan:9050."), report);
        Assertions.assertEquals(1L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
    }

    @Test
    public void theGaugeIsRegisteredOnTheFirstRoundAndFollowsTheCount() {
        Assertions.assertTrue(fe.gauges.isEmpty(), "not before the first round: the FE's metrics are not set up yet");

        watch.check();
        Assertions.assertEquals(Collections.singleton(LostWarehouseWatch.GAUGE), fe.gauges.keySet());
        Assertions.assertEquals(0L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());

        node(1L, "cn-a", 10001L);
        node(2L, "cn-b", 10001L);
        watch.check();
        Assertions.assertEquals(2L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
        Assertions.assertEquals(1, fe.gauges.size(), "registered once");
    }

    /**
     * The log line carries what the recovery needs: the warehouse ids, their worker groups and the addresses of
     * the nodes. The steps themselves are in the README.
     */
    @Test
    public void lostMetadataIsReportedWithTheNodesAndWhereTheStepsAre() {
        node(2L, "10.0.0.2", 10002L, 502L);
        node(1L, "10.0.0.1", 10001L, 501L);
        node(3L, "10.0.0.3", 0L);

        String report = watch.check();

        Assertions.assertEquals("Warehouse metadata is missing on this FE: 2 node(s) belong to 2 unknown warehouse(s)."
                + " Warehouse id 10001, worker group 501: node 1 at 10.0.0.1:9050."
                + " Warehouse id 10002, worker group 502: node 2 at 10.0.0.2:9050.", report);
        Assertions.assertEquals(2L, watch.orphanCount());
    }

    /**
     * Nodes of one warehouse are listed together, in node id order. Nodes of one warehouse that point at
     * different worker groups are listed per worker group.
     */
    @Test
    public void nodesAreGroupedByWarehouseAndWorkerGroup() {
        node(4L, "10.0.0.4", 10001L, 501L);
        node(3L, "10.0.0.3", 10002L, 503L);
        node(2L, "10.0.0.2", 10001L, 502L);
        node(1L, "10.0.0.1", 10001L, 501L);

        String report = watch.check();

        Assertions.assertTrue(report.startsWith("Warehouse metadata is missing on this FE: 4 node(s) belong to 2 unknown"
                + " warehouse(s). Warehouse id 10001, worker group 501: node 1 at 10.0.0.1:9050, node 4 at 10.0.0.4:9050."
                + " Warehouse id 10001, worker group 502: node 2 at 10.0.0.2:9050."
                + " Warehouse id 10002, worker group 503: node 3 at 10.0.0.3:9050."), report);
    }

    /**
     * The check runs every minute. The log line is repeated only every ten minutes, or at once when the set of
     * nodes changes, so a long outage does not flood fe.log. The gauge stays up the whole time.
     */
    @Test
    public void theReportIsRepeatedSparingly() {
        node(1L, "10.0.0.1", 10001L);

        Assertions.assertFalse(watch.check().isEmpty());
        fe.clock += LostWarehouseWatch.CHECK_INTERVAL_MS;
        Assertions.assertEquals("", watch.check());
        Assertions.assertEquals(1L, watch.orphanCount());

        fe.clock += LostWarehouseWatch.REPEAT_REPORT_MS;
        Assertions.assertFalse(watch.check().isEmpty(), "repeated after ten minutes");
        fe.clock += LostWarehouseWatch.CHECK_INTERVAL_MS;
        Assertions.assertEquals("", watch.check());

        node(2L, "10.0.0.2", 10001L);
        String changed = watch.check();
        Assertions.assertTrue(changed.contains(": 2 node(s) belong to 1 unknown warehouse(s)."),
                "reported at once when another node is affected: " + changed);
    }

    @Test
    public void theAllClearIsReportedOnce() {
        ComputeNode orphan = node(1L, "10.0.0.1", 10001L);
        watch.check();

        fe.nodes.remove(orphan);

        Assertions.assertEquals("Every node belongs to a known warehouse again", watch.check());
        Assertions.assertEquals(0L, watch.orphanCount());
        Assertions.assertEquals("", watch.check());
    }

    // ------------------------------------------------------------------ installation

    @Test
    public void installStartsOneWatchPerFe() {
        Assertions.assertTrue(LostWarehouseWatch.install(fe));
        Assertions.assertFalse(LostWarehouseWatch.install(fe), "the second call must not start another one");

        Assertions.assertEquals(Collections.singleton(LostWarehouseWatch.NAME), fe.periodicTasks.keySet());
        Assertions.assertEquals(LostWarehouseWatch.CHECK_INTERVAL_MS, fe.periodicIntervals.get(LostWarehouseWatch.NAME));

        node(1L, "10.0.0.1", 10001L);
        fe.periodicTasks.get(LostWarehouseWatch.NAME).run();
        Assertions.assertEquals(1L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
    }

    /**
     * End to end with a real manager: warehouses and nodes exist, then the FE restarts from an image that
     * an FE without the jar wrote. The nodes are still there, the warehouses are not.
     */
    @Test
    public void aRestartFromAnImageWithoutWarehousesRaisesTheAlarm() throws Exception {
        MultiWarehouseManager manager = new MultiWarehouseManager(fe);
        manager.initDefaultWarehouse();
        fe.knownWarehouseIds = manager::getAllWarehouseIds;
        manager.createWarehouse(new CreateWarehouseStmt(false, "research", null, null));
        fe.addNode(20001L, "cn-research-0", manager.getWarehouse("research"), true);
        fe.addNode(20000L, "cn-default-0", manager.getWarehouse("default_warehouse"), true);
        Runnable round = fe.periodicTasks.get(LostWarehouseWatch.NAME);
        Assertions.assertNotNull(round, "the manager starts the watch when the FE initialises it");

        round.run();
        Assertions.assertEquals(0L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());

        // The restart: a new manager that loaded nothing, in the same cluster.
        MultiWarehouseManager restarted = new MultiWarehouseManager(fe);
        restarted.initDefaultWarehouse();
        fe.knownWarehouseIds = restarted::getAllWarehouseIds;

        round.run();
        Assertions.assertEquals(1L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
        Assertions.assertSame(round, fe.periodicTasks.get(LostWarehouseWatch.NAME),
                "still the same watch after the second manager");

        // Dropping the stray node, the last step of the recovery, clears the alarm.
        fe.nodes.removeIf(node -> node.getId() == 20001L);
        round.run();
        Assertions.assertEquals(0L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
    }
}
