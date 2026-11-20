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

    @Test
    public void lostMetadataIsReportedWithTheNodesAndWhatToDo() {
        node(2L, "10.0.0.2", 10002L);
        node(1L, "10.0.0.1", 10001L);
        node(3L, "10.0.0.3", 0L);

        String report = watch.check();

        Assertions.assertTrue(report.startsWith("2 node(s) belong to a warehouse this FE does not know: "
                + "10.0.0.1:9050 (warehouse id 10001), 10.0.0.2:9050 (warehouse id 10002)."), report);
        Assertions.assertTrue(report.contains("FE without the warehouse extension jar"), report);
        Assertions.assertTrue(report.contains("do not restart it"), report);
        Assertions.assertTrue(report.contains("ALTER WAREHOUSE <name> SET ('replica_number' = '<its current value>')"), report);
        Assertions.assertTrue(report.contains("ALTER SYSTEM CREATE IMAGE"), report);
        Assertions.assertTrue(report.contains("ALTER SYSTEM DROP COMPUTE NODE"), report);
        Assertions.assertFalse(report.contains("10.0.0.3"), report);
        Assertions.assertEquals(2L, watch.orphanCount());
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
        Assertions.assertTrue(changed.startsWith("2 node(s)"), "reported at once when another node is affected: " + changed);
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
        UserWarehouseManager manager = new UserWarehouseManager(fe);
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
        UserWarehouseManager restarted = new UserWarehouseManager(fe);
        restarted.initDefaultWarehouse();
        fe.knownWarehouseIds = restarted::getAllWarehouseIds;

        round.run();
        Assertions.assertEquals(1L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
        Assertions.assertEquals(1, fe.periodicTasks.size(), "still one watch after the second manager");

        // Dropping the stray node, as the log line advises, clears the alarm.
        fe.nodes.removeIf(node -> node.getId() == 20001L);
        round.run();
        Assertions.assertEquals(0L, fe.gauges.get(LostWarehouseWatch.GAUGE).getAsLong());
    }
}
