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
import com.starrocks.common.Config;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    private long originalVersionLagTolerance;

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
        originalVersionLagTolerance = Config.load_primary_replica_version_lag_tolerance;
        Config.load_primary_replica_version_lag_tolerance = 64;
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

    @AfterEach
    public void tearDown() {
        Config.load_primary_replica_version_lag_tolerance = originalVersionLagTolerance;
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
        replicas.set(0, replica(1, 35, 1));
        assignments.put(2L, 10L);
        assignments.put(3L, 11L);
        Assertions.assertEquals(2, primary());

        // Tolerated lag must not make an overloaded replica a preferred primary.
        replicas.get(0).updateVersion(36);
        replicas.get(0).setVersionCount(2048);
        Assertions.assertEquals(2, primary());
        replicas.get(0).updateVersion(100);
        replicas.get(0).setVersionCount(100);
        Assertions.assertEquals(1, primary());

        // alter replicas must not bypass catch up for primary suitability
        replicas.set(0, replica(1, 1, 1));
        replicas.get(0).setState(Replica.ReplicaState.ALTER);
        Assertions.assertEquals(2, primary());
    }

    @ParameterizedTest(name = "tolerance={0}, lag={1}, preferred={2}")
    @CsvSource({
            "0, 0, true", "0, 1, false", "16, 16, true", "16, 17, false",
            "64, -1, true", "64, 0, true", "64, 1, true", "64, 63, true", "64, 64, true", "64, 65, false",
            "128, 128, true", "128, 129, false", "-1, 0, true", "-1, 1, false"
    })
    public void testVersionLagToleranceBoundary(long tolerance, long lag, boolean preferred) {
        Config.load_primary_replica_version_lag_tolerance = tolerance;
        replicas = new ArrayList<>(List.of(replica(1, 1000 - lag, 100),
                replica(2, 1000, 100), replica(3, 1000, 100)));
        assignments.put(2L, 1L);
        assignments.put(3L, 2L);
        int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, 1, 1000, replicas, List.of());
        Assertions.assertEquals(preferred ? 1L : 2L, replicas.get(selected).getBackendId());
    }

    @Test
    public void testVersionLagToleranceUpdatesTakeEffect() {
        replicas.set(0, replica(1, 99, 100));
        assignments.put(2L, 1L);
        assignments.put(3L, 2L);
        Config.load_primary_replica_version_lag_tolerance = 0;
        Assertions.assertEquals(2, primary());
        Config.load_primary_replica_version_lag_tolerance = 64;
        Assertions.assertEquals(1, primary());
        Config.load_primary_replica_version_lag_tolerance = 0;
        Assertions.assertEquals(2, primary());
    }

    @Test
    public void testHighVersionLagToleranceKeepsLaggingReplicaEligible() {
        Config.load_primary_replica_version_lag_tolerance = Long.MAX_VALUE;
        // Use initialized replicas: a high tolerance allows even a large known version gap.
        replicas = new ArrayList<>(List.of(replica(1, 1, 100),
                replica(2, Long.MAX_VALUE, 100), replica(3, Long.MAX_VALUE, 100)));
        assignments.put(2L, 1L);
        assignments.put(3L, 2L);
        int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, 1, Long.MAX_VALUE, replicas, List.of());
        Assertions.assertEquals(1L, replicas.get(selected).getBackendId());
    }

    @Test
    public void testVersionLagToleranceHandlesLargeVersions() {
        Config.load_primary_replica_version_lag_tolerance = 64;
        assignments.put(2L, 1L);
        assignments.put(3L, 2L);
        for (long lag : new long[] {64, 65}) {
            replicas = new ArrayList<>(List.of(replica(1, Long.MAX_VALUE - lag, 100),
                    replica(2, Long.MAX_VALUE, 100), replica(3, Long.MAX_VALUE, 100)));
            int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, 1, Long.MAX_VALUE, replicas, List.of());
            Assertions.assertEquals(lag == 64 ? 1L : 2L, replicas.get(selected).getBackendId());
        }
    }

    @ParameterizedTest(name = "lag={0}, colocated={1}")
    @CsvSource({"64, true", "65, false"})
    public void testColocatedSelectionUsesVersionLagTolerance(long lag, boolean colocated) {
        long visibleVersion = 100 + lag;
        replicas.get(0).updateVersion(visibleVersion);
        LocalTablet base = new LocalTablet(10);
        replicas.forEach(replica -> base.addReplica(replica, false));
        LocalTablet mv = new LocalTablet(11);
        for (long backendId = 1; backendId <= 3; backendId++) {
            mv.addReplica(replica(backendId, backendId == 2 ? visibleVersion : 100, 100), false);
        }
        int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, base.getId(), visibleVersion,
                replicas, List.of(base, mv));
        Assertions.assertEquals(colocated, selected >= 0);
    }

    @ParameterizedTest(name = "tolerance={0}, lag={1}, colocated={2}")
    @CsvSource({"0, 1, false", "64, 64, true", "64, 65, false", "128, 128, true", "128, 129, false"})
    public void testUniformColocatedLagRespectsTolerance(long tolerance, long lag, boolean colocated) {
        Config.load_primary_replica_version_lag_tolerance = tolerance;
        LocalTablet base = new LocalTablet(10);
        replicas.forEach(replica -> base.addReplica(replica, false));
        LocalTablet mv = new LocalTablet(11);
        for (long backendId = 1; backendId <= 3; backendId++) {
            mv.addReplica(replica(backendId, 100, 100), false);
        }
        int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, base.getId(), 100 + lag,
                replicas, List.of(base, mv));
        Assertions.assertEquals(colocated, selected >= 0,
                "Uniform lag beyond tolerance must trigger independent loading");
        Assertions.assertTrue(OlapTableSink.findPrimaryReplica(assignments, infoService, base.getId(), 100 + lag,
                replicas, List.of()) >= 0, "Lagging replicas must remain available for independent loading");
    }

    @Test
    public void testUniformWriteFailuresDisableColocatedLoading() {
        LocalTablet base = new LocalTablet(10);
        replicas.forEach(replica -> {
            replica.setLastWriteFail(true);
            base.addReplica(replica, false);
        });
        Assertions.assertEquals(-1, OlapTableSink.findPrimaryReplica(assignments, infoService, base.getId(), 100,
                replicas, List.of(base)));
        Assertions.assertTrue(primary() > 0, "Failed replicas must remain independent fallback candidates");
    }

    @Test
    public void testReportedFailuresAndRecovery() {
        assignments.put(2L, 1L);
        assignments.put(3L, 2L);
        replicas.set(0, replica(1, 99, 100));
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

    @ParameterizedTest(name = "lag={0}")
    @CsvSource({"0", "65"})
    public void testActiveReplicaPreferredOverDecommission(long lag) {
        replicas.get(0).setState(Replica.ReplicaState.DECOMMISSION);
        replicas.get(0).setVersionCount(1);
        replicas.set(1, replica(2, 100 - lag, 2048));
        replicas.set(2, replica(3, 100 - lag, 2048));
        assignments.put(2L, 10L);
        assignments.put(3L, 11L);
        Assertions.assertEquals(2, primary(),
                "Active replicas must remain preferred despite lag, pressure, and more assignments");
    }

    @Test
    public void testHealthyDecommissionPreferredOverReportedFailures() {
        replicas.get(0).setState(Replica.ReplicaState.DECOMMISSION);
        replicas.get(1).setLastWriteFail(true);
        replicas.get(2).setLastWriteFail(true);
        assignments.put(1L, 10L);
        Assertions.assertEquals(1, primary(), "A healthy decommissioned replica remains a last-resort primary");
    }

    @Test
    public void testDecommissionRemainsLastResortWhenAllReplicasFail() {
        replicas.get(0).setState(Replica.ReplicaState.DECOMMISSION);
        replicas.forEach(replica -> replica.setLastWriteFail(true));
        assignments.put(2L, 10L);
        assignments.put(3L, 11L);
        Assertions.assertEquals(2, primary());
    }

    @Test
    public void testOnlyDecommissionedReplicaRemainsEligible() {
        replicas = new ArrayList<>(List.of(replica(1, 100, 100)));
        replicas.get(0).setState(Replica.ReplicaState.DECOMMISSION);
        Assertions.assertEquals(1, primary());
    }

    @Test
    public void testColocatedSelectionAvoidsDecommissionedMvReplica() {
        assignments.put(2L, 10L);
        assignments.put(3L, 11L);
        LocalTablet base = new LocalTablet(10);
        replicas.forEach(replica -> base.addReplica(replica, false));
        LocalTablet mv = new LocalTablet(11);
        for (long backendId = 1; backendId <= 3; backendId++) {
            Replica replica = replica(backendId, 100, 100);
            if (backendId == 1) {
                replica.setState(Replica.ReplicaState.DECOMMISSION);
            }
            mv.addReplica(replica, false);
        }
        int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, base.getId(), 100,
                replicas, List.of(base, mv));
        Assertions.assertEquals(2L, replicas.get(selected).getBackendId());
    }

    @Test
    public void testColocatedDecommissionFallsBackToIndependentPrimaries() {
        LocalTablet base = new LocalTablet(10);
        LocalTablet mv = new LocalTablet(11);
        for (int i = 0; i < replicas.size(); i++) {
            Replica baseReplica = replicas.get(i);
            if (baseReplica.getBackendId() != 1) {
                baseReplica.setState(Replica.ReplicaState.DECOMMISSION);
            }
            base.addReplica(baseReplica, false);
            Replica mvReplica = replica(baseReplica.getBackendId(), 100, 100);
            if (mvReplica.getBackendId() != 2) {
                mvReplica.setState(Replica.ReplicaState.DECOMMISSION);
            }
            mv.addReplica(mvReplica, false);
        }
        Assertions.assertEquals(-1, OlapTableSink.findPrimaryReplica(assignments, infoService, base.getId(), 100,
                replicas, List.of(base, mv)));
        Assertions.assertEquals(1, primary());
        List<Replica> mvReplicas = new ArrayList<>();
        for (long backendId = 1; backendId <= 3; backendId++) {
            mvReplicas.add(mv.getReplicaByBackendId(backendId));
        }
        int selected = OlapTableSink.findPrimaryReplica(assignments, infoService, mv.getId(), 100, mvReplicas, List.of());
        Assertions.assertEquals(2L, mvReplicas.get(selected).getBackendId());
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
                    index.addTablet(tablet, new TabletMeta(1, table.getId(), partition.getId(), indexId,
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

    @ParameterizedTest(name = "{0}, stream={1}")
    @CsvSource({
            "last_write_fail, false", "last_write_fail, true",
            "error_state, false", "error_state, true",
            "version_lag, false", "version_lag, true",
            "decommission, false", "decommission, true"
    })
    public void testDegradedCommonPrimaryFallsBackToHealthyIndependentPrimaries(String degradation, boolean stream)
            throws Exception {
        OlapTable table = createTable("colocated_degraded_" + degradation + "_" + stream);
        addColocatedIndexes(table);
        table.setWriteQuorum("ALL");
        PhysicalPartition partition = table.getAllPhysicalPartitions().iterator().next();
        boolean versionLag = degradation.equals("version_lag");
        long visibleVersion = 100 + Config.load_primary_replica_version_lag_tolerance + 1;
        if (versionLag) {
            partition.updateVisibleVersion(visibleVersion);
        }
        Map<Long, Long> expectedPrimaries = new HashMap<>();
        List<MaterializedIndex> indexes = partition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL);
        for (int i = 0; i < indexes.size(); i++) {
            long healthyBackendId = 10001L + i;
            for (Tablet tablet : indexes.get(i).getTablets()) {
                expectedPrimaries.put(tablet.getId(), healthyBackendId);
                for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                    replica.setVersionCount(100);
                    if (replica.getBackendId() == healthyBackendId) {
                        if (versionLag) {
                            replica.updateVersion(visibleVersion);
                        }
                    } else {
                        switch (degradation) {
                            case "last_write_fail" -> replica.setLastWriteFail(true);
                            case "error_state" -> replica.setIsErrorState(true);
                            case "version_lag" -> Assertions.assertEquals(100, replica.getVersion());
                            case "decommission" -> replica.setState(Replica.ReplicaState.DECOMMISSION);
                            default -> throw new IllegalArgumentException(degradation);
                        }
                    }
                }
            }
        }
        AtomicBoolean eligible = new AtomicBoolean(false);
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                return eligible.get();
            }
        };
        // Independent selection has a healthy primary for every tablet, with all secondaries retained.
        TOlapTableSink independent = stream ? streamSink(table) : sqlSink(table);
        Assertions.assertEquals(expectedPrimaries.size(), independent.getLocation().getTabletsSize());
        for (TTabletLocation location : independent.getLocation().getTablets()) {
            Assertions.assertEquals(expectedPrimaries.get(location.getTablet_id()), location.getNode_ids().get(0));
            Assertions.assertEquals(Set.of(10001L, 10002L, 10003L), new HashSet<>(location.getNode_ids()));
        }
        eligible.set(true);
        TOlapTableSink fallback = stream ? streamSink(table) : sqlSink(table);
        Assertions.assertAll(
                () -> Assertions.assertFalse(fallback.isEnable_colocate_mv_index(),
                        "No backend is healthy across all indexes; use independent primaries"),
                () -> Assertions.assertEquals(independent.getLocation(), fallback.getLocation(),
                        "Colocated loading must fall back to the available healthy independent primaries"),
                () -> Assertions.assertTrue(fallback.isEnable_replicated_storage()),
                () -> Assertions.assertEquals(table.writeQuorum(), fallback.getWrite_quorum_type()));
    }

    @ParameterizedTest(name = "colocated={0}, stream={1}, tolerance={2}, lag={3}")
    @CsvSource({
            "false, false, 64, 64", "false, true, 64, 64", "true, false, 64, 64", "true, true, 64, 64",
            "false, false, 64, 65", "false, true, 64, 65", "true, false, 64, 65", "true, true, 64, 65",
            "false, false, 128, 128", "false, true, 128, 128", "true, false, 128, 128", "true, true, 128, 128",
            "false, false, 128, 129", "false, true, 128, 129", "true, false, 128, 129", "true, true, 128, 129"
    })
    public void testAllReplicasLaggingStillPlan(boolean colocated, boolean stream, long tolerance, long lag) throws Exception {
        Config.load_primary_replica_version_lag_tolerance = tolerance;
        OlapTable table = createTable("all_replicas_lagging_" + colocated + "_" + stream + "_" + tolerance + "_" + lag);
        addColocatedIndexes(table);
        table.setWriteQuorum("ALL");
        Set<Long> expectedTablets = new HashSet<>();
        for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
            // Model a visible-version advance before the corresponding replica reports arrive.
            partition.updateVisibleVersion(100 + lag);
            for (MaterializedIndex index : partition.getMaterializedIndices(MaterializedIndex.IndexExtState.ALL)) {
                for (Tablet tablet : index.getTablets()) {
                    expectedTablets.add(tablet.getId());
                    for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                        Assertions.assertEquals(100, replica.getVersion());
                        replica.setVersionCount(100);
                    }
                }
            }
        }
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                return colocated;
            }
        };
        TOlapTableSink sink = stream ? streamSink(table) : sqlSink(table);
        boolean expectedColocation = colocated && lag <= tolerance;
        Assertions.assertEquals(expectedColocation, sink.isEnable_colocate_mv_index(),
                "Colocated loading must fall back when uniform lag exceeds the tolerance");
        if (expectedColocation) {
            assertCommonPrimaries(sink);
        }
        Assertions.assertTrue(sink.isEnable_replicated_storage());
        Assertions.assertEquals(table.writeQuorum(), sink.getWrite_quorum_type());
        Assertions.assertEquals(expectedTablets.size(), sink.getLocation().getTabletsSize());
        Set<Long> actualTablets = new HashSet<>();
        for (TTabletLocation location : sink.getLocation().getTablets()) {
            actualTablets.add(location.getTablet_id());
            Assertions.assertEquals(3, location.getNode_idsSize());
            Assertions.assertEquals(Set.of(10001L, 10002L, 10003L), new HashSet<>(location.getNode_ids()));
        }
        Assertions.assertEquals(expectedTablets, actualTablets);
    }

    @ParameterizedTest(name = "stream={0}, tolerance={1}, lag={2}")
    @CsvSource({
            "false, 64, 1", "true, 64, 1", "false, 64, 64", "true, 64, 64", "false, 64, 65", "true, 64, 65",
            "false, 128, 128", "true, 128, 128", "false, 128, 129", "true, 128, 129"
    })
    public void testUniformIndexLagRespectsTolerance(boolean stream, long tolerance, long lag) throws Exception {
        Config.load_primary_replica_version_lag_tolerance = tolerance;
        OlapTable table = createTable("uniform_index_lag_" + stream + "_" + tolerance + "_" + lag);
        addColocatedIndexes(table);
        table.setWriteQuorum("ALL");
        for (PhysicalPartition partition : table.getAllPhysicalPartitions()) {
            partition.updateVisibleVersion(100 + lag);
            // Only the base index has caught up; every replica of each MV index is uniformly behind.
            for (Tablet tablet : partition.getBaseIndex().getTablets()) {
                for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                    replica.updateVersion(100 + lag);
                }
            }
        }
        new MockUp<OlapTable>() {
            @Mock
            public boolean isEnableColocateMVIndex() {
                return true;
            }
        };
        TOlapTableSink sink = stream ? streamSink(table) : sqlSink(table);
        boolean expectedColocation = lag <= tolerance;
        Assertions.assertEquals(expectedColocation, sink.isEnable_colocate_mv_index(),
                "Uniform MV lag beyond tolerance must trigger independent loading");
        if (expectedColocation) {
            assertCommonPrimaries(sink);
        }
        Assertions.assertTrue(sink.isEnable_replicated_storage());
        Assertions.assertEquals(table.writeQuorum(), sink.getWrite_quorum_type());
        Assertions.assertEquals(18, sink.getLocation().getTabletsSize());
        for (TTabletLocation location : sink.getLocation().getTablets()) {
            Assertions.assertEquals(Set.of(10001L, 10002L, 10003L), new HashSet<>(location.getNode_ids()));
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
