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
import com.starrocks.common.proc.ProcResult;
import com.starrocks.system.ComputeNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class UserWarehouseTest {
    private FakeFeEnvironment fe;
    private UserWarehouse research;

    @BeforeEach
    public void before() {
        fe = new FakeFeEnvironment();
        research = new UserWarehouse(10001L, "research", "ad hoc research", 501L, 2, 1_000L, 2_000L);
        research.attach(fe);
    }

    @Test
    public void exposesWhatItWasBuiltWith() {
        Assertions.assertEquals(10001L, research.getId());
        Assertions.assertEquals("research", research.getName());
        Assertions.assertEquals("ad hoc research", research.getComment());
        Assertions.assertEquals(501L, research.getWorkerGroupId());
        Assertions.assertEquals(2, research.getReplicaNumber());
        Assertions.assertEquals(1_000L, research.getCreateTimeMs());
        Assertions.assertEquals(2_000L, research.getUpdateTimeMs());
        Assertions.assertEquals(Long.valueOf(501L), research.getAnyWorkerGroupId());
        Assertions.assertEquals(Collections.singletonList(501L), research.getWorkerGroupIds());
        Assertions.assertEquals("UserWarehouse{id=10001, name=research, workerGroupId=501, replicaNumber=2}",
                research.toString());
    }

    /**
     * There is no SUSPEND, so the warehouse always counts as active, whether or not a node is alive. For a
     * warehouse that reports otherwise, the FE logs failed heartbeats only at DEBUG.
     */
    @Test
    public void isAlwaysActive() {
        Assertions.assertTrue(research.isAvailable());
        Assertions.assertEquals(-1L, research.getResumeTime());
    }

    @Test
    public void aChangedCopyLeavesTheOriginalAlone() throws Exception {
        UserWarehouse changed = research.withReplicaNumber(5, 9_000L);

        Assertions.assertEquals(2, research.getReplicaNumber());
        Assertions.assertEquals(2_000L, research.getUpdateTimeMs());

        Assertions.assertEquals(5, changed.getReplicaNumber());
        Assertions.assertEquals(9_000L, changed.getUpdateTimeMs());
        Assertions.assertEquals(research.getId(), changed.getId());
        Assertions.assertEquals(research.getName(), changed.getName());
        Assertions.assertEquals(research.getComment(), changed.getComment());
        Assertions.assertEquals(research.getWorkerGroupId(), changed.getWorkerGroupId());
        Assertions.assertEquals(research.getCreateTimeMs(), changed.getCreateTimeMs());
        Assertions.assertFalse(changed.isRetired());

        // The copy talks to the same FE as the original.
        fe.addNode(20001L, "cn-a", changed, true);
        Assertions.assertEquals("1", changed.getWarehouseInfo().get(3));
    }

    @Test
    public void aNodeJoinsTheWorkerGroupOfTheWarehouse() throws Exception {
        ComputeNode node = new ComputeNode(20001L, "cn-a", 9050);

        research.addNodeToCNGroup(node, null);

        Assertions.assertEquals(501L, node.getWorkerGroupId());
        Assertions.assertEquals(10001L, node.getWarehouseId());
        Assertions.assertDoesNotThrow(() -> research.addNodeToCNGroup(new ComputeNode(20002L, "cn-b", 9050), ""));
    }

    @Test
    public void cnGroupNamesAreRefused() {
        ComputeNode node = new ComputeNode(20001L, "cn-a", 9050);

        DdlException e = Assertions.assertThrows(DdlException.class, () -> research.addNodeToCNGroup(node, "g1"));
        Assertions.assertTrue(e.getMessage().contains("CNGroup feature not implemented"), e.getMessage());
        Assertions.assertNotEquals(10001L, node.getWarehouseId());

        Assertions.assertThrows(DdlException.class, () -> research.validateRemoveNodeFromCNGroup(node, "g1"));
        Assertions.assertDoesNotThrow(() -> research.validateRemoveNodeFromCNGroup(node, ""));
        Assertions.assertDoesNotThrow(() -> research.validateRemoveNodeFromCNGroup(node, null));
    }

    @Test
    public void aRetiredWarehouseTakesNoNewNodes() throws Exception {
        ComputeNode node = new ComputeNode(20001L, "cn-a", 9050);
        research.retire();
        Assertions.assertTrue(research.isRetired());

        DdlException e = Assertions.assertThrows(DdlException.class, () -> research.addNodeToCNGroup(node, ""));
        Assertions.assertTrue(e.getMessage().contains("research"), e.getMessage());
        Assertions.assertNotEquals(10001L, node.getWarehouseId());

        research.reinstate();
        Assertions.assertFalse(research.isRetired());
        research.addNodeToCNGroup(node, "");
        Assertions.assertEquals(10001L, node.getWarehouseId());
    }

    @Test
    public void cnGroupStatementsAreNotSupported() {
        for (org.junit.jupiter.api.function.Executable statement : new org.junit.jupiter.api.function.Executable[] {
                () -> research.createCNGroup(null),
                () -> research.dropCNGroup(null),
                () -> research.enableCNGroup(null),
                () -> research.disableCNGroup(null),
                () -> research.alterCNGroup(null)}) {
            DdlException e = Assertions.assertThrows(DdlException.class, statement);
            Assertions.assertTrue(e.getMessage().contains("CN groups are not supported"), e.getMessage());
        }
        Assertions.assertDoesNotThrow(() -> research.replayInternalOpLog("{}"));
    }

    @Test
    public void showStatementsReadTheNodesOfThisWarehouseOnly() throws Exception {
        UserWarehouse etl = new UserWarehouse(10002L, "etl", null, 502L, 1, 1_000L, 1_000L);
        etl.attach(fe);
        fe.addNode(20002L, "cn-b", research, true);
        fe.addNode(20001L, "cn-a", research, false);
        fe.addNode(20003L, "cn-c", etl, false);
        fe.workerIds.put(20002L, 7L);

        List<String> summary = research.getWarehouseInfo();
        Assertions.assertEquals(WarehouseRows.SUMMARY_COLUMN_COUNT, summary.size());
        Assertions.assertEquals(WarehouseRows.STATE_AVAILABLE, summary.get(2));
        Assertions.assertEquals("2", summary.get(3));
        Assertions.assertEquals(WarehouseRows.STATE_UNAVAILABLE, etl.getWarehouseInfo().get(2));

        List<List<String>> nodes = research.getWarehouseNodesInfo();
        Assertions.assertEquals(2, nodes.size());
        Assertions.assertEquals("20001", nodes.get(0).get(3));
        Assertions.assertEquals("-1", nodes.get(0).get(4));
        Assertions.assertEquals("20002", nodes.get(1).get(3));
        Assertions.assertEquals("7", nodes.get(1).get(4));

        ProcResult proc = research.fetchResult();
        Assertions.assertEquals(WarehouseRows.NODE_COLUMNS, proc.getColumnNames());
        Assertions.assertEquals(nodes, proc.getRows());
    }

    @Test
    public void onlyFormatsThisJarKnowsAreAccepted() throws Exception {
        Assertions.assertDoesNotThrow(research::gsonPostProcess);

        for (int badVersion : new int[] {0, -1, UserWarehouse.FORMAT_VERSION + 1}) {
            java.lang.reflect.Field field = UserWarehouse.class.getDeclaredField("formatVersion");
            field.setAccessible(true);
            field.setInt(research, badVersion);

            IOException e = Assertions.assertThrows(IOException.class, research::gsonPostProcess);
            Assertions.assertTrue(e.getMessage().contains("'research' is stored in format " + badVersion), e.getMessage());
        }
    }
}
