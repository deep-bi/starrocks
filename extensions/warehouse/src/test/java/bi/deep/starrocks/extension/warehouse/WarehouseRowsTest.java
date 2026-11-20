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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WarehouseRowsTest {
    private static final long CREATED = 1_700_000_000_000L;
    private static final long UPDATED = 1_700_000_060_000L;

    private final UserWarehouse research = new UserWarehouse(10001L, "research", "ad hoc research", 501L, 2,
            CREATED, UPDATED);

    private static ComputeNode node(long id, String host, boolean alive) {
        ComputeNode node = new ComputeNode(id, host, 9050);
        node.setAlive(alive);
        return node;
    }

    @Test
    public void summaryOfAnEmptyWarehouse() {
        List<String> row = WarehouseRows.summary(research, Collections.emptyList());

        Assertions.assertEquals(Arrays.asList(
                "10001", "research", "UNAVAILABLE", "0", "1", "1", "1", "0", "0",
                TimeUtils.longToTimeString(CREATED), "", TimeUtils.longToTimeString(UPDATED),
                "replica_number=2", "ad hoc research"), row);
        Assertions.assertEquals(WarehouseRows.SUMMARY_COLUMN_COUNT, row.size());
    }

    @Test
    public void aWarehouseIsAvailableWhenAnyNodeIsAlive() {
        List<ComputeNode> allDead = Arrays.asList(node(1L, "a", false), node(2L, "b", false));
        List<ComputeNode> oneAlive = Arrays.asList(node(1L, "a", false), node(2L, "b", true));

        Assertions.assertEquals("UNAVAILABLE", WarehouseRows.summary(research, allDead).get(2));
        Assertions.assertEquals("2", WarehouseRows.summary(research, allDead).get(3));
        Assertions.assertEquals("AVAILABLE", WarehouseRows.summary(research, oneAlive).get(2));
    }

    @Test
    public void aMissingCommentIsShownEmpty() {
        UserWarehouse plain = new UserWarehouse(10002L, "etl", null, 502L, 1, CREATED, CREATED);

        Assertions.assertEquals("", WarehouseRows.summary(plain, Collections.emptyList()).get(13));
    }

    @Test
    public void nodeRowsFollowTheColumnsOfShowNodes() {
        ComputeNode node = node(20001L, "10.0.0.7", true);
        node.setBePort(9060);
        node.setHttpPort(8040);
        node.setBrpcPort(8060);
        node.setStarletPort(9070);
        node.setLastStartTime(CREATED);
        node.setLastUpdateMs(UPDATED);
        node.setVersion("4.1.7-b81ff9c");
        node.setCpuCores(8);
        node.updateResourceUsage(3, 0L, 255);

        List<List<String>> rows = WarehouseRows.nodes(research, Collections.singletonList(node), id -> 42L);

        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals(WarehouseRows.NODE_COLUMNS.size(), rows.get(0).size());
        Assertions.assertEquals(Arrays.asList(
                "research", "", "501", "20001", "42", "10.0.0.7", "9050", "9060", "8040", "8060", "9070",
                TimeUtils.longToTimeString(CREATED), TimeUtils.longToTimeString(UPDATED), "true", "",
                "4.1.7-b81ff9c", "3", "8", "0.00", "25.50", ""), rows.get(0));
    }

    @Test
    public void nodeRowsAreOrderedByNodeIdAndTolerateMissingValues() {
        // A node the FE has never heard from: no version, no ports, no heartbeat.
        ComputeNode silent = new ComputeNode(20002L, null, 9050);
        ComputeNode first = node(20001L, "cn-a", false);

        List<List<String>> rows = WarehouseRows.nodes(research, Arrays.asList(silent, first), id -> -1L);

        Assertions.assertEquals("20001", rows.get(0).get(3));
        Assertions.assertEquals("20002", rows.get(1).get(3));
        List<String> silentRow = rows.get(1);
        Assertions.assertEquals("-1", silentRow.get(4));
        Assertions.assertEquals("", silentRow.get(5));
        Assertions.assertEquals("false", silentRow.get(13));
        Assertions.assertEquals(TimeUtils.longToTimeString(0L), silentRow.get(11));
        for (String cell : silentRow) {
            Assertions.assertNotNull(cell, "SHOW rows must not contain null: " + silentRow);
        }
    }

    @Test
    public void theNodeColumnsMatchTheFe() {
        Assertions.assertEquals(21, WarehouseRows.NODE_COLUMNS.size());
        Assertions.assertEquals("WarehouseName", WarehouseRows.NODE_COLUMNS.get(0));
        Assertions.assertEquals("CNGroupName", WarehouseRows.NODE_COLUMNS.get(20));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> WarehouseRows.NODE_COLUMNS.add("x"));
    }
}
