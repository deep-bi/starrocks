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

package com.starrocks.lake.compaction;

import bi.deep.starrocks.extension.warehouse.MultiWarehouseManager;
import bi.deep.starrocks.extension.warehouse.UserDefinedWarehouse;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.DefaultWarehouse;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.WarehouseComputeResource;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mocked;
import mockit.Verifications;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class WarehouseCompactionSchedulingTest {
    @Test
    public void saturatedCompactionWarehouseStopsThePartitionScan(
            @Mocked GlobalStateMgr state, @Mocked RunMode runMode, @Injectable UserDefinedWarehouse warehouse,
            @Injectable CompactionMgr compactions, @Injectable CompactionJob runningJob) throws Exception {
        long warehouseId = 10001L;
        long groupId = 501L;
        ComputeNode node = new ComputeNode(7001L, "localhost", 9050);
        node.setAlive(true);
        List<Long> routedTables = new ArrayList<>();
        MultiWarehouseManager manager = new MultiWarehouseManager() {
            @Override
            public ComputeResource getCompactionComputeResource(long tableId) {
                routedTables.add(tableId);
                return super.getCompactionComputeResource(tableId);
            }
        };
        PartitionIdentifier running = new PartitionIdentifier(1L, 10L, 100L);
        List<PartitionStatisticsSnapshot> backlog = List.of(candidate(11L), candidate(12L), candidate(13L));
        new Expectations() {
            {
                warehouse.getName();
                result = "etl";
                warehouse.getId();
                result = warehouseId;
                warehouse.getAnyWorkerGroupId();
                result = groupId;
                warehouse.getWorkerGroupIds();
                result = List.of(groupId);
                RunMode.isSharedDataMode();
                result = true;
                GlobalStateMgr.getCurrentState();
                result = state;
                state.isLeader();
                result = true;
                state.isReady();
                result = true;
                state.getWarehouseMgr();
                result = manager;
                state.getStarOSAgent().getWorkersByWorkerGroup(groupId);
                result = List.of(node.getId());
                state.getNodeMgr().getClusterInfo().getBackendOrComputeNode(node.getId());
                result = node;
                runningJob.getResult();
                result = CompactionTask.TaskResult.NOT_FINISHED;
                runningJob.getComputeResource();
                result = WarehouseComputeResource.of(warehouseId);
                runningJob.getNumTabletCompactionTasks();
                result = 1;
                compactions.choosePartitionsToCompact(Set.of(running), Set.of());
                result = backlog;
            }
        };
        manager.addWarehouse(new DefaultWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_ID,
                WarehouseManager.DEFAULT_WAREHOUSE_NAME));
        manager.addWarehouse(warehouse);
        CompactionScheduler scheduler = new CompactionScheduler(compactions, state.getNodeMgr().getClusterInfo(),
                state.getGlobalTransactionMgr(), state, "");
        scheduler.getRunningCompactions().put(running, runningJob);

        String savedWarehouse = Config.lake_compaction_warehouse;
        int savedLimit = Config.lake_compaction_max_tasks;
        boolean savedUnitTest = FeConstants.runningUnitTest;
        try {
            Config.lake_compaction_warehouse = "etl";
            Config.lake_compaction_max_tasks = 1;
            FeConstants.runningUnitTest = false;
            scheduler.runOneCycle();
        } finally {
            FeConstants.runningUnitTest = savedUnitTest;
            Config.lake_compaction_max_tasks = savedLimit;
            Config.lake_compaction_warehouse = savedWarehouse;
        }

        Assertions.assertEquals(List.of(11L), routedTables, "one running task already fills the configured warehouse");
        Assertions.assertEquals(Set.of(running), scheduler.getRunningCompactions().keySet());
        new Verifications() {
            {
                state.getStarOSAgent().getWorkersByWorkerGroup(groupId);
                times = 1;
            }
        };
    }

    private static PartitionStatisticsSnapshot candidate(long tableId) {
        PartitionStatistics stats = new PartitionStatistics(new PartitionIdentifier(1L, tableId, tableId * 10));
        stats.setCompactionScore(new Quantiles(10, 10, 10));
        return stats.getSnapshot();
    }
}
