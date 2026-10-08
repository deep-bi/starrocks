// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
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

package com.starrocks.planner;

import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.common.FeConstants;
import com.starrocks.load.streamload.StreamLoadInfo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TDataSink;
import com.starrocks.thrift.TDataSinkType;
import com.starrocks.thrift.TFileFormatType;
import com.starrocks.thrift.TFileType;
import com.starrocks.thrift.TOlapTableIndexTablets;
import com.starrocks.thrift.TOlapTablePartition;
import com.starrocks.thrift.TOlapTablePartitionParam;
import com.starrocks.thrift.TOlapTableSink;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TStreamLoadPutRequest;
import com.starrocks.thrift.TTabletLocation;
import com.starrocks.thrift.TUniqueId;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadPrimarySelectionTest {
    private static ConnectContext context;
    private static StarRocksAssert starRocksAssert;
    private SystemInfoService infoService;
    private List<Replica> replicas;
    private Map<Long, Long> assignments;

    @BeforeAll
    public static void beforeClass() throws Exception {
        FeConstants.runningUnitTest = true;
        UtFrameUtils.createMinStarRocksCluster();
        UtFrameUtils.addMockBackend(10002, "127.0.0.2", 9060);
        UtFrameUtils.addMockBackend(10003, "127.0.0.3", 9060);
        context = UtFrameUtils.createDefaultCtx();
        starRocksAssert = new StarRocksAssert(context);
        starRocksAssert.withDatabase("load_primary_test").useDatabase("load_primary_test");
    }

    @BeforeEach
    public void setUp() {
        infoService = new SystemInfoService();
        replicas = new ArrayList<>();
        assignments = new HashMap<>();
        for (long id = 1; id <= 3; id++) {
            Backend backend = new Backend(id, "127.0.0." + id, 9050);
            backend.setAlive(true);
            infoService.addBackend(backend);
            replicas.add(replica(id, 100, 100));
        }
    }

    private static Replica replica(long backendId, long version, long count) {
        Replica replica = new Replica(backendId, backendId, Replica.ReplicaState.NORMAL, version, 0);
        replica.setVersionCount(count);
        return replica;
    }

    private long primary() {
        return primary(1);
    }

    private long primary(long tabletId) {
        int index = OlapTableSink.findPrimaryReplica(assignments, infoService, tabletId, 100, replicas, List.of());
        return index < 0 ? -1 : replicas.get(index).getBackendId();
    }

    @Test
    public void testCatchUpBeforePressureAndBalancing() {
        replicas.set(0, replica(1, 90, 1));
        assignments.put(2L, 10L);
        assignments.put(3L, 11L);
        Assertions.assertEquals(2, primary());

        // catch up alone does not make an overloaded replica a preferred primary
        replicas.get(0).updateVersion(100);
        replicas.get(0).setVersionCount(2048);
        Assertions.assertEquals(2, primary());
        replicas.get(0).setVersionCount(100);
        Assertions.assertEquals(1, primary());

        // alter replicas must not bypass catch up for primary suitability
        replicas.set(0, replica(1, 1, 1));
        replicas.get(0).setState(Replica.ReplicaState.ALTER);
        Assertions.assertEquals(2, primary());
    }

    @Test
    public void testReportedFailuresAndRecovery() {
        assignments.put(2L, 1L);
        assignments.put(3L, 2L);
        Replica first = replicas.get(0);
        first.setIsErrorState(true);
        Assertions.assertEquals(2, primary());
        first.setIsErrorState(false);
        first.setLastWriteFail(true);
        Assertions.assertEquals(2, primary());
        first.setLastWriteFail(false);
        infoService.getBackend(1).setLastWriteFail(true);
        Assertions.assertEquals(2, primary());
        infoService.getBackend(1).setLastWriteFail(false);
        first.updateLastFailedVersion(101);
        Assertions.assertEquals(2, primary());
        first.updateVersion(101);
        Assertions.assertEquals(1, primary());
        Assertions.assertFalse(first.isBad());
    }

    @Test
    public void testUnknownReportsAndRelativePressure() {
        replicas.get(0).setVersionCount(-1);
        replicas.get(1).setVersionCount(Long.MAX_VALUE);
        replicas.get(2).setVersionCount(-1);
        // unknown is not zero pressure, even when all known counts are very high
        Assertions.assertEquals(2, primary());
        replicas.get(1).setVersionCount(-1);
        assignments.put(1L, 2L);
        assignments.put(3L, 1L);
        Assertions.assertEquals(2, primary());

        replicas.get(0).setVersionCount(100);
        replicas.get(1).setVersionCount(110);
        replicas.get(2).setVersionCount(2048);
        Map<Long, Long> counts = new HashMap<>();
        assignments.clear();
        for (int i = 0; i < 6; i++) {
            long backendId = primary();
            counts.merge(backendId, 1L, Long::sum);
            assignments.merge(backendId, 1L, Long::sum);
        }
        Assertions.assertEquals(Map.of(1L, 3L, 2L, 3L), counts);
        replicas.get(2).setVersionCount(100);
        Assertions.assertEquals(3, primary());
    }

    @Test
    public void testDeterministicDegradedFallback() {
        replicas.forEach(replica -> replica.setLastWriteFail(true));
        long expected = primary();
        for (int i = 0; i < 3; i++) {
            Collections.rotate(replicas, 1);
            Assertions.assertEquals(expected, primary());
        }
        replicas.replaceAll(replica -> replica(replica.getBackendId(), 90, -1));
        Assertions.assertEquals(expected, primary());
        assignments.put(expected, 1L);
        long alternative = primary();
        Assertions.assertNotEquals(expected, alternative);
        infoService.dropBackend(infoService.getBackend(alternative));
        long remaining = 6 - expected - alternative;
        Assertions.assertEquals(remaining, primary());
        infoService.getBackend(expected).setAlive(false);
        infoService.getBackend(remaining).setAlive(false);
        Assertions.assertEquals(-1, primary());
    }

    @Test
    public void testNearbyVersionCountsRemainBalanced() {
        for (long[] counts : new long[][] {{63, 64, 64}, {127, 128, 140}, {4, 8, 15}, {511, 512, 512}, {0, 1, 1}}) {
            assignments.clear();
            for (int i = 0; i < replicas.size(); i++) {
                replicas.get(i).setVersionCount(counts[i]);
            }
            for (long tabletId = 1; tabletId <= 30; tabletId++) {
                assignments.merge(primary(tabletId), 1L, Long::sum);
            }
            Assertions.assertEquals(Map.of(1L, 10L, 2L, 10L, 3L, 10L), assignments);
        }
    }

    @Test
    public void testPressureComparisonUsesBestHealthTier() {
        replicas.get(0).setVersionCount(1);
        replicas.get(0).setIsErrorState(true);
        replicas.get(2).setVersionCount(200);
        for (long tabletId = 1; tabletId <= 30; tabletId++) {
            assignments.merge(primary(tabletId), 1L, Long::sum);
        }
        Assertions.assertEquals(Map.of(2L, 15L, 3L, 15L), assignments);
    }

    @Test
    public void testIndependentLoadsDistributeTiesAcrossBackends() {
        for (long id = 4; id <= 6; id++) {
            Backend backend = new Backend(id, "127.0.0." + id, 9050);
            backend.setAlive(true);
            infoService.addBackend(backend);
            replicas.add(replica(id, 100, 100));
        }
        List<Replica> allReplicas = new ArrayList<>(replicas);
        Map<Long, Long> counts = new HashMap<>();
        long tabletId = 1000;
        // exercise every placement of three replicas on six backends, with a fresh assignment map for each load
        for (int a = 0; a < 4; a++) {
            for (int b = a + 1; b < 5; b++) {
                for (int c = b + 1; c < 6; c++) {
                    replicas = new ArrayList<>(List.of(allReplicas.get(a), allReplicas.get(b), allReplicas.get(c)));
                    for (int load = 0; load < 60; load++, tabletId++) {
                        assignments.clear();
                        long backendId = primary(tabletId);
                        counts.merge(backendId, 1L, Long::sum);
                        Collections.rotate(replicas, 1);
                        Assertions.assertEquals(backendId, primary(tabletId));
                    }
                }
            }
        }
        Assertions.assertEquals(Set.of(1L, 2L, 3L, 4L, 5L, 6L), counts.keySet());
        // expect roughly 200 of the 1200 primaries per backend, allowing broad deterministic sampling variance
        for (long count : counts.values()) {
            Assertions.assertTrue(count > 100 && count < 300, counts.toString());
        }
    }

    private static OlapTable createTable(String name) throws Exception {
        return createTable(name, false);
    }

    private static OlapTable createTable(String name, boolean partitioned) throws Exception {
        String partitions = partitioned ? "PARTITION BY RANGE(k) (PARTITION p1 VALUES LESS THAN ('10'), "
                + "PARTITION p2 VALUES LESS THAN ('20')) " : "";
        starRocksAssert.withTable("CREATE TABLE " + name + " (k int) DUPLICATE KEY(k) "
                + partitions
                + "DISTRIBUTED BY HASH(k) BUCKETS 6 "
                + "PROPERTIES ('replication_num'='3', 'replicated_storage'='true')");
        return (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                .getTable("load_primary_test", name);
    }

    private static TOlapTableSink sqlSink(OlapTable table) throws Exception {
        return sqlSink(table, "(1)");
    }

    private static TOlapTableSink sqlSink(OlapTable table, String values) throws Exception {
        return UtFrameUtils.getPlanAndFragment(context, "insert into " + table.getName() + " values " + values)
                .second.getTopFragment().getSink().toThrift().getOlap_table_sink();
    }

    private static TOlapTableSink streamSink(OlapTable table) throws Exception {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("load_primary_test");
        TStreamLoadPutRequest request = new TStreamLoadPutRequest();
        request.setTxnId(1);
        request.setLoadId(new TUniqueId(2, 3));
        request.setFileType(TFileType.FILE_STREAM);
        request.setFormatType(TFileFormatType.FORMAT_CSV_PLAIN);
        StreamLoadInfo info = StreamLoadInfo.fromTStreamLoadPutRequest(request, db);
        return new StreamLoadPlanner(db, table, info).plan(info.getId())
                .getFragment().getOutput_sink().getOlap_table_sink();
    }

    private static void assertBothPlanners(OlapTable table, Map<Long, Long> expectedPrimaries,
                                           Set<Long> expectedReplicas) throws Exception {
        for (TOlapTableSink sink : List.of(sqlSink(table), streamSink(table))) {
            Assertions.assertTrue(sink.isEnable_replicated_storage());
            Assertions.assertEquals(table.writeQuorum(), sink.getWrite_quorum_type());
            Map<Long, Long> counts = new HashMap<>();
            for (TTabletLocation location : sink.getLocation().getTablets()) {
                Assertions.assertEquals(expectedReplicas, new HashSet<>(location.getNode_ids()));
                counts.merge(location.getNode_ids().get(0), 1L, Long::sum);
            }
            Assertions.assertEquals(expectedPrimaries, counts);
        }
    }

    @Test
    public void testRestartReportsInSqlAndStreamLoad() throws Exception {
        OlapTable table = createTable("restart_reports");
        List<Replica> recovering = new ArrayList<>();
        for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
            partition.updateVisibleVersion(100);
            for (Tablet tablet : partition.getBaseIndex().getTablets()) {
                for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                    boolean restarting = replica.getBackendId() == 10001;
                    replica.updateVersion(restarting ? 90 : 100);
                    replica.setVersionCount(restarting ? 2048 : 100);
                    if (restarting) {
                        recovering.add(replica);
                    }
                }
            }
        }
        Map<Long, Long> healthyPrimaries = Map.of(10002L, 3L, 10003L, 3L);
        Set<Long> allReplicas = Set.of(10001L, 10002L, 10003L);
        assertBothPlanners(table, healthyPrimaries, allReplicas);
        recovering.forEach(replica -> replica.updateVersion(100));
        assertBothPlanners(table, healthyPrimaries, allReplicas);
        recovering.forEach(replica -> replica.setVersionCount(-1));
        assertBothPlanners(table, healthyPrimaries, allReplicas);
        recovering.forEach(replica -> {
            replica.setVersionCount(100);
            replica.setIsErrorState(true);
        });
        // primary preferences neither remove secondaries nor weaken all quorum
        table.setWriteQuorum("ALL");
        assertBothPlanners(table, healthyPrimaries, allReplicas);
        recovering.forEach(replica -> replica.setIsErrorState(false));
        assertBothPlanners(table, Map.of(10001L, 2L, 10002L, 2L, 10003L, 2L), allReplicas);

        recovering.forEach(replica -> replica.setBad(true));
        assertQuorumFailure(table);
        table.setWriteQuorum("MAJORITY");
        assertBothPlanners(table, healthyPrimaries, Set.of(10002L, 10003L));
        for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
            for (Tablet tablet : partition.getBaseIndex().getTablets()) {
                ((LocalTablet) tablet).getReplicaByBackendId(10002).setBad(true);
            }
        }
        assertQuorumFailure(table);
        table.setWriteQuorum("ONE");
        assertBothPlanners(table, Map.of(10003L, 6L), Set.of(10003L));
    }

    private static void assertQuorumFailure(OlapTable table) {
        Exception sqlError = Assertions.assertThrows(Exception.class, () -> sqlSink(table));
        Assertions.assertTrue(sqlError.getMessage().contains("Check quorum number failed"), sqlError.getMessage());
        Exception streamError = Assertions.assertThrows(Exception.class, () -> streamSink(table));
        Assertions.assertTrue(streamError.getMessage().contains("Check quorum number failed"), streamError.getMessage());
    }

    @Test
    public void testNearbyCountsInSqlAndStreamLoad() throws Exception {
        OlapTable table = createTable("nearby_counts");
        for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
            for (Tablet tablet : partition.getBaseIndex().getTablets()) {
                for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                    replica.setVersionCount(replica.getBackendId() == 10001 ? 127 : 128);
                }
            }
        }
        assertBothPlanners(table, Map.of(10001L, 2L, 10002L, 2L, 10003L, 2L), Set.of(10001L, 10002L, 10003L));
    }

    private static void addColocatedIndexes(OlapTable table) {
        // two additional indexes check both the preference across indexes and reuse across every bucket
        for (int indexNum = 1; indexNum <= 2; indexNum++) {
            long indexId = GlobalStateMgr.getCurrentState().getNextId();
            table.setIndexMeta(indexId, table.getName() + "_mv" + indexNum, table.getBaseSchema(),
                    0, 0, (short) 1, TStorageType.COLUMN, table.getKeysType());
            table.getIndexMetaByIndexId(indexId).setColocateMVIndex(true);
            for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
                MaterializedIndex index = new MaterializedIndex(indexId, MaterializedIndex.IndexState.NORMAL);
                for (int bucket = 0; bucket < partition.getBaseIndex().getTablets().size(); bucket++) {
                    LocalTablet tablet = new LocalTablet(GlobalStateMgr.getCurrentState().getNextId());
                    for (long backendId = 10001; backendId <= 10003; backendId++) {
                        Replica replica = replica(backendId, 100, 100);
                        if (backendId == 10001) {
                            replica.setVersionCount(2048);
                        }
                        tablet.addReplica(replica, false);
                    }
                    index.addTablet(tablet, new TabletMeta(1, table.getId(), partition.getId(), indexId, 0,
                            TStorageMedium.HDD), false);
                }
                partition.createRollupIndex(index);
            }
        }
        for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
            partition.updateVisibleVersion(100);
            for (Tablet tablet : partition.getBaseIndex().getTablets()) {
                for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                    replica.updateVersion(100);
                    replica.setVersionCount(100);
                }
            }
        }
    }

    private static void removeCommonPrimary(PhysicalPartition partition, int bucket) {
        List<MaterializedIndex> indexes = partition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL);
        for (int i = 0; i < indexes.size(); i++) {
            ((LocalTablet) indexes.get(i).getTablets().get(bucket)).getReplicaByBackendId(10001 + i).setBad(true);
        }
    }

    private static void assertCommonPrimaries(TOlapTableSink sink) {
        Map<Long, Long> primaries = new HashMap<>();
        for (TTabletLocation location : sink.getLocation().getTablets()) {
            primaries.put(location.getTablet_id(), location.getNode_ids().get(0));
        }
        for (TOlapTablePartition partition : sink.getPartition().getPartitions()) {
            List<Long> baseTablets = partition.getIndexes().get(0).getTablets();
            for (TOlapTableIndexTablets index : partition.getIndexes()) {
                for (int bucket = 0; bucket < baseTablets.size(); bucket++) {
                    Assertions.assertEquals(primaries.get(baseTablets.get(bucket)),
                            primaries.get(index.getTablets().get(bucket)));
                }
            }
        }
    }

    @Test
    public void testColocatedIndexesKeepACommonHealthyPrimary() throws Exception {
        OlapTable table = createTable("colocated_reports");
        addColocatedIndexes(table);
        PhysicalPartition partition = table.getAllPhysicalPartitions().iterator().next();
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                return true;
            }
        };
        TOlapTablePartitionParam partitionParam = new TOlapTablePartitionParam();
        TOlapTablePartition tPartition = new TOlapTablePartition();
        tPartition.setId(partition.getId());
        partitionParam.addToPartitions(tPartition);
        List<TTabletLocation> locations = OlapTableSink.createLocation(table, partitionParam, true).getTablets();
        Assertions.assertEquals(18, locations.size());
        for (int bucket = 0; bucket < 6; bucket++) {
            long primary = locations.get(bucket).getNode_ids().get(0);
            Assertions.assertNotEquals(10001, primary);
            Assertions.assertEquals(primary, locations.get(6 + bucket).getNode_ids().get(0));
            Assertions.assertEquals(primary, locations.get(12 + bucket).getNode_ids().get(0));
            Assertions.assertEquals(3, locations.get(bucket).getNode_idsSize());
        }
        // each index still has a majority, but there is no backend common to the whole bucket
        removeCommonPrimary(partition, 0);
        locations = OlapTableSink.createLocation(table, partitionParam, true).getTablets();
        Assertions.assertEquals(18, locations.size());
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(2, locations.get(i * 6).getNode_idsSize());
            Assertions.assertFalse(locations.get(i * 6).getNode_ids().contains(10001L + i));
        }
    }

    @Test
    public void testColocatedFallbackInSqlAndStreamLoad() throws Exception {
        OlapTable table = createTable("colocated_fallback", true);
        addColocatedIndexes(table);
        PhysicalPartition affected = table.getPartition("p2").getDefaultPhysicalPartition();
        removeCommonPrimary(affected, 5);
        AtomicBoolean eligible = new AtomicBoolean(true);
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                return eligible.get();
            }
        };
        for (boolean stream : new boolean[] {false, true}) {
            eligible.set(true);
            TOlapTableSink fallback = stream ? streamSink(table) : sqlSink(table, "(1), (11)");
            Assertions.assertFalse(fallback.isEnable_colocate_mv_index());
            Assertions.assertTrue(fallback.isEnable_replicated_storage());
            Assertions.assertEquals(table.writeQuorum(), fallback.getWrite_quorum_type());
            Assertions.assertEquals(2, fallback.getPartition().getPartitionsSize());
            Assertions.assertEquals(36, fallback.getLocation().getTabletsSize());
            eligible.set(false);
            TOlapTableSink independent = stream ? streamSink(table) : sqlSink(table, "(1), (11)");
            // the whole sink must be rebuilt, including partitions before the conflicting bucket
            Assertions.assertEquals(independent.getLocation(), fallback.getLocation());
        }
        eligible.set(true);
        table.setWriteQuorum("ALL");
        for (boolean stream : new boolean[] {false, true}) {
            Exception error = Assertions.assertThrows(Exception.class, () -> {
                if (stream) {
                    streamSink(table);
                } else {
                    sqlSink(table, "(1), (11)");
                }
            });
            Assertions.assertTrue(error.getMessage().contains("Check quorum number failed"), error.getMessage());
        }
    }

    @Test
    public void testColocateEligibilityIsReadOncePerPlan() throws Exception {
        OlapTable table = createTable("colocated_snapshot", true);
        addColocatedIndexes(table);
        AtomicBoolean initiallyEligible = new AtomicBoolean();
        AtomicInteger evaluations = new AtomicInteger();
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                return evaluations.getAndIncrement() == 0 ? initiallyEligible.get() : !initiallyEligible.get();
            }
        };
        for (boolean eligible : new boolean[] {false, true}) {
            initiallyEligible.set(eligible);
            for (boolean stream : new boolean[] {false, true}) {
                evaluations.set(0);
                TOlapTableSink sink = stream ? streamSink(table) : sqlSink(table, "(1), (11)");
                Assertions.assertEquals(1, evaluations.get());
                Assertions.assertEquals(eligible, sink.isEnable_colocate_mv_index());
                Assertions.assertEquals(2, sink.getPartition().getPartitionsSize());
                if (eligible) {
                    assertCommonPrimaries(sink);
                }
            }
        }
    }

    @Test
    public void testDoubleWriteSinkUsesItsOwnFallbackDecision() throws Exception {
        OlapTable table = createTable("colocated_double_write", true);
        addColocatedIndexes(table);
        Partition source = table.getPartition("p1");
        Partition target = table.getPartition("p2");
        removeCommonPrimary(target.getDefaultPhysicalPartition(), 5);
        table.addDoubleWritePartition(source.getId(), target.getId());
        AtomicInteger evaluations = new AtomicInteger();
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                evaluations.incrementAndGet();
                return true;
            }
        };
        DescriptorTable descriptors = new DescriptorTable();
        TupleDescriptor tuple = descriptors.createTupleDescriptor("double_write");
        for (Column column : table.getBaseSchema()) {
            SlotDescriptor slot = descriptors.addSlotDescriptor(tuple);
            slot.setColumn(column);
            slot.setIsMaterialized(true);
        }
        OlapTableSink sink = new OlapTableSink(table, tuple, List.of(source.getId()), table.writeQuorum(),
                true, false, false);
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("load_primary_test");
        sink.init(new TUniqueId(4, 5), 1, db.getId(), 60);
        sink.complete();
        TDataSink result = sink.toThrift();
        Assertions.assertEquals(TDataSinkType.MULTI_OLAP_TABLE_SINK, result.getType());
        Assertions.assertEquals(1, evaluations.get());
        TOlapTableSink original = result.getMulti_olap_table_sinks().get(0).getOlap_table_sink();
        TOlapTableSink copy = result.getMulti_olap_table_sinks().get(1).getOlap_table_sink();
        Assertions.assertTrue(original.isEnable_colocate_mv_index());
        assertCommonPrimaries(original);
        Assertions.assertFalse(copy.isEnable_colocate_mv_index());
        Assertions.assertTrue(copy.isEnable_replicated_storage());
        Assertions.assertEquals(18, copy.getLocation().getTabletsSize());
    }
}
