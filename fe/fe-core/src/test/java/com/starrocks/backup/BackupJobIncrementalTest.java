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

package com.starrocks.backup;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.backup.BackupJob.BackupJobState;
import com.starrocks.backup.BackupJobInfo.BackupIndexInfo;
import com.starrocks.backup.BackupJobInfo.BackupPartitionInfo;
import com.starrocks.backup.BackupJobInfo.BackupPhysicalPartitionInfo;
import com.starrocks.backup.BackupJobInfo.BackupTableInfo;
import com.starrocks.backup.BackupJobInfo.BackupTabletInfo;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.FsBroker;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.TableName;
import com.starrocks.catalog.Tablet;
import com.starrocks.common.Config;
import com.starrocks.common.FeConstants;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.concurrent.lock.LockManager;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.TableRefPersist;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.server.NodeMgr;
import com.starrocks.sql.ast.KeysType;
import com.starrocks.system.Backend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.task.SnapshotTask;
import com.starrocks.task.UploadTask;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTaskType;
import com.starrocks.thrift.TUploadReq;
import com.starrocks.transaction.GtidGenerator;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

// Change detection (test-design.md section 3) and the snapshot content it decides (TC-020..034).
// Each test backs up one table, because CatalogMocker reuses tablet ids across tables.
public class BackupJobIncrementalTest {
    private static final long REPO_ID = 20000;
    private static final String TBL2 = CatalogMocker.TEST_TBL2_NAME;
    private static final String TBL4 = CatalogMocker.TEST_TBL4_NAME;
    private static final long P1 = CatalogMocker.TEST_PH_PARTITION1_ID;
    private static final long P2 = CatalogMocker.TEST_PH_PARTITION2_ID;

    // The base is itself an incremental: its tablets' data files are held by FULL, their headers by BASE.
    private static final String FULL = "full";
    private static final String BASE = "base";
    private static final String LABEL = "inc";
    private static final String MD5_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String MD5_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String MD5_C = "cccccccccccccccccccccccccccccccc";
    private static final String MD5_D = "dddddddddddddddddddddddddddddddd";
    private static final String MD5_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String SNAPSHOT_PATH = "/snapshot";

    @Mocked
    private GlobalStateMgr globalStateMgr;
    @Mocked
    private LocalMetastore localMetastore;
    @Mocked
    private EditLog editLog;

    private Database db;
    private final AtomicLong nextId = new AtomicLong(90000);
    private final Repository repo = new Repository(REPO_ID, "repo", false, "my_repo",
            new BlobStorage("broker", Maps.newHashMap()));
    private boolean originEnableMetricCalculator;

    private final class MockRepositoryMgr extends RepositoryMgr {
        @Override
        public Repository getRepo(long repoId) {
            return repo;
        }
    }

    private final class MockBackupHandler extends BackupHandler {
        private final RepositoryMgr repoMgr = new MockRepositoryMgr();

        MockBackupHandler(GlobalStateMgr globalStateMgr) {
            super(globalStateMgr);
        }

        @Override
        public RepositoryMgr getRepoMgr() {
            return repoMgr;
        }
    }

    @BeforeAll
    public static void start() {
        Config.tmp_dir = "./";
        new File(BackupHandler.BACKUP_ROOT_DIR.toString()).mkdirs();
    }

    @BeforeEach
    public void setUp() throws Exception {
        globalStateMgr = GlobalStateMgr.getCurrentState();
        Deencapsulation.setField(globalStateMgr, "backupHandler", new MockBackupHandler(globalStateMgr));
        db = CatalogMocker.mockDb();
        makeAllReplicasHealthy();

        SystemInfoService infoService = new SystemInfoService();
        for (long beId : List.of(CatalogMocker.BACKEND1_ID, CatalogMocker.BACKEND2_ID, CatalogMocker.BACKEND3_ID)) {
            Backend backend = new Backend(beId, "127.0.0.1", 9050);
            backend.setAlive(true);
            infoService.addBackend(backend);
        }
        NodeMgr nodeMgr = new NodeMgr();
        Deencapsulation.setField(nodeMgr, "systemInfo", infoService);
        LockManager lockManager = new LockManager();

        new Expectations(globalStateMgr) {
            {
                globalStateMgr.getLockManager();
                minTimes = 0;
                result = lockManager;

                globalStateMgr.getGtidGenerator();
                minTimes = 0;
                result = new GtidGenerator();

                globalStateMgr.getLocalMetastore();
                minTimes = 0;
                result = localMetastore;

                globalStateMgr.getNodeMgr();
                minTimes = 0;
                result = nodeMgr;

                globalStateMgr.getNextId();
                minTimes = 0;
                result = new Delegate() {
                    long getNextId() {
                        return nextId.getAndIncrement();
                    }
                };

                globalStateMgr.getEditLog();
                minTimes = 0;
                result = editLog;
            }
        };

        new Expectations(localMetastore) {
            {
                localMetastore.getDb(anyLong);
                minTimes = 0;
                result = db;

                localMetastore.getTable(anyString, anyString);
                minTimes = 0;
                result = new Delegate() {
                    com.starrocks.catalog.Table getTable(String dbName, String tblName) {
                        return db.getTable(tblName);
                    }
                };
            }
        };

        new MockUp<AgentTaskExecutor>() {
            @Mock
            public void submit(AgentBatchTask task) {
            }
        };

        new MockUp<Repository>() {
            @Mock
            Status upload(String localFilePath, String remoteFilePath) {
                return Status.OK;
            }

            @Mock
            Status getBrokerAddress(Long beId, GlobalStateMgr globalStateMgr, List<FsBroker> brokerAddrs) {
                brokerAddrs.add(new FsBroker());
                return Status.OK;
            }
        };

        AgentTaskQueue.clearAllTasks();
        originEnableMetricCalculator = Config.enable_metric_calculator;
        Config.enable_metric_calculator = false;
        MetricRepo.init();
    }

    @AfterEach
    public void tearDown() {
        Config.enable_metric_calculator = originEnableMetricCalculator;
        AgentTaskQueue.clearAllTasks();
    }

    // TC-020 (P1)
    @Test
    public void unchangedPartitionGetsNoSnapshotTaskAndKeepsTheBaseManifests() throws Exception {
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));

        Assertions.assertEquals(Set.of(), snapshotTablets(job));
        for (long tabletId : tablets(TBL2, P1, P2)) {
            Assertions.assertEquals(baseManifest(tabletId), job.getTabletManifests().get(tabletId));
        }
    }

    // TC-021 (P2)
    @Test
    public void newerVersionSnapshotsThePartitionWithReuseLists() throws Exception {
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);

        Assertions.assertEquals(tablets(TBL2, P1), snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : tablets(TBL2, P1)) {
            Assertions.assertEquals(List.of(dataFile(tabletId, MD5_A), headerFile(tabletId, MD5_B)),
                    reuseList(uploads, tabletId));
        }
        for (long tabletId : tablets(TBL2, P2)) {
            Assertions.assertEquals(baseManifest(tabletId), job.getTabletManifests().get(tabletId));
        }
    }

    // TC-022 (P3)
    @Test
    public void sameVersionWithAnotherVersionTimeSnapshotsThePartitionWithReuseLists() throws Exception {
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion(), p1.getVisibleVersionTime() + 1);

        Assertions.assertEquals(tablets(TBL2, P1), snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : tablets(TBL2, P1)) {
            Assertions.assertEquals(List.of(dataFile(tabletId, MD5_A), headerFile(tabletId, MD5_B)),
                    reuseList(uploads, tabletId));
        }
    }

    // TC-023 (P4): a truncated partition has a new physical id and new tablets.
    @Test
    public void newPhysicalPartitionIdSnapshotsThePartitionWithoutReuse() throws Exception {
        Set<Long> p1Tablets = tablets(TBL2, P1);
        IncrementalBase base = baseOf(TBL2, null,
                copy -> Deencapsulation.setField(copy.getPhysicalPartition(P1), "id", P1 + 100000),
                info -> renumberTablets(info, p1Tablets));
        BackupJob job = incrementalJob(TBL2, base);

        Assertions.assertEquals(p1Tablets, snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : p1Tablets) {
            Assertions.assertEquals(List.of(), reuseList(uploads, tabletId));
        }
    }

    // TC-024 (P5)
    @Test
    public void partitionAddedAfterTheBaseIsSnapshottedWithoutReuse() throws Exception {
        BackupJob job = incrementalJob(TBL2,
                baseOf(TBL2, List.of(CatalogMocker.TEST_PARTITION2_NAME), copy -> { }, info -> { }));

        Assertions.assertEquals(tablets(TBL2, P1), snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : tablets(TBL2, P1)) {
            Assertions.assertEquals(List.of(), reuseList(uploads, tabletId));
        }
    }

    // TC-025 (P6): each index identity field, one at a time, then a rollup added and a rollup dropped.
    @Test
    public void indexIdentityChangeSnapshotsTheWholeTable() throws Exception {
        long baseIndexMetaId = CatalogMocker.TEST_TBL2_ID;
        List<Consumer<OlapTable>> changes = List.of(
                copy -> indexMeta(copy, baseIndexMetaId).setSchemaId(indexMeta(copy, baseIndexMetaId).getSchemaId() + 1),
                copy -> indexMeta(copy, baseIndexMetaId).setSchemaVersion(
                        indexMeta(copy, baseIndexMetaId).getSchemaVersion() + 1),
                copy -> Deencapsulation.setField(indexMeta(copy, baseIndexMetaId), "schemaHash",
                        indexMeta(copy, baseIndexMetaId).getSchemaHash() + 1),
                copy -> copy.setIndexMeta(99999L, "dropped_rollup", CatalogMocker.TEST_ROLLUP_SCHEMA, 0, 12345,
                        (short) 1, TStorageType.COLUMN, KeysType.AGG_KEYS));
        for (Consumer<OlapTable> change : changes) {
            AgentTaskQueue.clearAllTasks();
            BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, change, info -> { }));

            Assertions.assertEquals(tablets(TBL2, P1, P2), snapshotTablets(job));
            List<UploadTask> uploads = uploadTasks(job);
            for (long tabletId : tablets(TBL2, P1, P2)) {
                Assertions.assertEquals(List.of(dataFile(tabletId, MD5_A), headerFile(tabletId, MD5_B)),
                        reuseList(uploads, tabletId));
            }
        }

        // The rollup was added after the base: its tablets are new, the base index's tablets are not.
        AgentTaskQueue.clearAllTasks();
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> {
            copy.deleteIndexInfo(CatalogMocker.TEST_ROLLUP_NAME);
            for (PhysicalPartition partition : copy.getPhysicalPartitions()) {
                partition.deleteMaterializedIndexByIndexId(CatalogMocker.TEST_ROLLUP_ID);
            }
        }, info -> { }));
        Set<Long> rollupTablets = Set.of(CatalogMocker.TEST_ROLLUP_TABLET_P1_ID, CatalogMocker.TEST_ROLLUP_TABLET_P2_ID);

        Assertions.assertEquals(tablets(TBL2, P1, P2), snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : tablets(TBL2, P1, P2)) {
            List<String> expected = rollupTablets.contains(tabletId) ? List.of()
                    : List.of(dataFile(tabletId, MD5_A), headerFile(tabletId, MD5_B));
            Assertions.assertEquals(expected, reuseList(uploads, tabletId));
        }
    }

    // TC-026 (P7): dropped and recreated under the same name, so new table id and new tablets.
    @Test
    public void tableWithAnotherIdIsSnapshottedWithoutReuse() throws Exception {
        Set<Long> allTablets = tablets(TBL2, P1, P2);
        IncrementalBase base = baseOf(TBL2, null,
                copy -> Deencapsulation.setField(copy, "id", CatalogMocker.TEST_TBL2_ID + 100000),
                info -> renumberTablets(info, allTablets));
        BackupJob job = incrementalJob(TBL2, base);

        Assertions.assertEquals(allTablets, snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : allTablets) {
            Assertions.assertEquals(List.of(), reuseList(uploads, tabletId));
        }
    }

    // TC-027 (P8)
    @Test
    public void tableNotInTheBaseIsSnapshottedWithoutReuse() throws Exception {
        Set<Long> allTablets = tablets(TBL2, P1, P2);
        IncrementalBase base = baseOf(TBL2, null, copy -> {
            Deencapsulation.setField(copy, "id", CatalogMocker.TEST_TBL2_ID + 100000);
            copy.setName("another_table");
        }, info -> renumberTablets(info, allTablets));
        BackupJob job = incrementalJob(TBL2, base);

        Assertions.assertEquals(allTablets, snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : allTablets) {
            Assertions.assertEquals(List.of(), reuseList(uploads, tabletId));
        }
    }

    // TC-028: test_tbl4's logical partition p1 has the physical sub-partitions 40011, 40002 and 40015.
    @Test
    public void subPartitionsAreDecidedOneByOne() throws Exception {
        BackupJob job = incrementalJob(TBL4, baseOf(TBL4, null, copy -> { }, info -> { }));
        PhysicalPartition changed = physicalPartition(TBL4, CatalogMocker.TEST_PARTITION2_ID);
        changed.setVisibleVersion(changed.getVisibleVersion() + 1, changed.getVisibleVersionTime() + 1);

        Assertions.assertEquals(tablets(TBL4, CatalogMocker.TEST_PARTITION2_ID), snapshotTablets(job));
        for (long tabletId : tablets(TBL4, CatalogMocker.TEST_PH_PARTITION1_ID)) {
            Assertions.assertEquals(baseManifest(tabletId), job.getTabletManifests().get(tabletId));
        }
    }

    // TC-029 (P9)
    @Test
    public void baseFromAnotherClusterIsNeverReused() throws Exception {
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> info.dbId = 99999));

        Assertions.assertEquals(tablets(TBL2, P1, P2), snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : tablets(TBL2, P1, P2)) {
            Assertions.assertEquals(List.of(), reuseList(uploads, tabletId));
        }
        finishUploads(job, uploads, tabletId -> List.of(dataFile(tabletId, MD5_A), headerFile(tabletId, MD5_B)));
        for (long tabletId : tablets(TBL2, P1, P2)) {
            for (ManifestEntry entry : job.getTabletManifests().get(tabletId)) {
                Assertions.assertEquals(LABEL, entry.getHolder());
            }
        }
    }

    // TC-031: change detection relies on the base's __meta keeping each physical partition's version time.
    @Test
    public void backupMetaRoundTripKeepsTheVisibleVersionTime() throws Exception {
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion(), 1234567890123L);

        BackupMeta meta = roundTrip(new BackupMeta(List.of(copyOf(TBL2, null))));

        OlapTable read = (OlapTable) meta.getTable(TBL2);
        Assertions.assertEquals(1234567890123L, read.getPhysicalPartition(P1).getVisibleVersionTime());
    }

    // TC-032 (F1-F3)
    @Test
    public void manifestNamesTheBaseHolderOnlyForFilesWithTheSameNameAndMd5() throws Exception {
        long tabletId = CatalogMocker.TEST_BASE_TABLET_P1_ID;
        IncrementalBase base = baseOf(TBL2, null, copy -> { }, info -> forEachTablet(info, tablet -> {
            if (tablet.id == tabletId) {
                tablet.files.add(new ManifestEntry(tabletId + "_1.dat." + MD5_C, FULL, false));
            }
        }));
        BackupJob job = incrementalJob(TBL2, base);
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        snapshotTablets(job);

        List<UploadTask> uploads = uploadTasks(job, id -> List.of(id + "_0.dat", id + "_1.dat", id + "_2.dat", id + ".hdr"));
        finishUploads(job, uploads, id -> List.of(
                dataFile(id, MD5_A),               // F1: same name and md5 as in the base
                id + "_1.dat." + MD5_D,            // F2: same name, another md5
                id + "_2.dat." + MD5_E,            // F3: not in the base
                headerFile(id, MD5_E)));           // F2

        Assertions.assertEquals(List.of(
                new ManifestEntry(dataFile(tabletId, MD5_A), FULL, false),
                new ManifestEntry(tabletId + "_1.dat." + MD5_D, LABEL, true),
                new ManifestEntry(tabletId + "_2.dat." + MD5_E, LABEL, true),
                new ManifestEntry(headerFile(tabletId, MD5_E), LABEL, true)), job.getTabletManifests().get(tabletId));
    }

    // TC-033 (P10)
    @Test
    public void legacyBaseIsAcceptedButNothingIsReused() throws Exception {
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> {
            info.snapshotFormatVersion = BackupJobInfo.SNAPSHOT_FORMAT_VERSION_LEGACY;
            forEachTablet(info, tablet -> tablet.files.clear());
        }));

        Assertions.assertEquals(tablets(TBL2, P1, P2), snapshotTablets(job));
        List<UploadTask> uploads = uploadTasks(job);
        for (long tabletId : tablets(TBL2, P1, P2)) {
            Assertions.assertEquals(List.of(), reuseList(uploads, tabletId));
        }
    }

    // TC-034
    @Test
    public void incrementalUploadsGoToTheirOwnContentDirectory() throws Exception {
        BackupJob incremental = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        snapshotTablets(incremental);
        for (UploadTask task : uploadTasks(incremental)) {
            for (String dest : task.getSrcToDestPath().values()) {
                Assertions.assertTrue(dest.contains("/__ss_" + LABEL + "/__ss_content_inc/"), dest);
            }
        }

        AgentTaskQueue.clearAllTasks();
        BackupJob full = new BackupJob("full_label", CatalogMocker.TEST_DB_ID, CatalogMocker.TEST_DB_NAME,
                tableRefs(TBL2), 13600 * 1000, globalStateMgr, REPO_ID);
        snapshotTablets(full);
        for (UploadTask task : uploadTasks(full)) {
            for (String dest : task.getSrcToDestPath().values()) {
                Assertions.assertTrue(dest.contains("/__ss_full_label/__ss_content/"), dest);
            }
        }
    }

    // ---- helpers ----

    private BackupJob incrementalJob(String tableName, IncrementalBase base) {
        BackupJob job = new BackupJob(LABEL, CatalogMocker.TEST_DB_ID, CatalogMocker.TEST_DB_NAME,
                tableRefs(tableName), 13600 * 1000, globalStateMgr, REPO_ID);
        job.setIncrementalBase(base);
        return job;
    }

    private List<TableRefPersist> tableRefs(String tableName) {
        return Lists.newArrayList(
                new TableRefPersist(new TableName(CatalogMocker.TEST_DB_NAME, tableName), null));
    }

    // Builds the base as the FE reads it from the repository: __meta through a file round trip, and an
    // __info whose every tablet has a manifest. The hooks change the base side before it is summarized.
    private IncrementalBase baseOf(String tableName, List<String> reservedPartitions,
                                   Consumer<OlapTable> changeCopy, Consumer<BackupJobInfo> changeInfo)
            throws IOException {
        BackupMeta meta = roundTrip(new BackupMeta(List.of(copyOf(tableName, reservedPartitions))));
        OlapTable copy = (OlapTable) meta.getTable(tableName);
        changeCopy.accept(copy);

        BackupJobInfo info = BackupJobInfo.fromCatalog(System.currentTimeMillis(), BASE,
                CatalogMocker.TEST_DB_NAME, CatalogMocker.TEST_DB_ID, List.of(copy), Maps.newHashMap());
        info.snapshotFormatVersion = BackupJobInfo.SNAPSHOT_FORMAT_VERSION;
        forEachTablet(info, tablet -> tablet.files.addAll(baseManifest(tablet.id)));
        changeInfo.accept(info);
        return IncrementalBase.of(info, meta);
    }

    private static List<ManifestEntry> baseManifest(long tabletId) {
        return Lists.newArrayList(
                new ManifestEntry(dataFile(tabletId, MD5_A), FULL, false),
                new ManifestEntry(headerFile(tabletId, MD5_B), BASE, true));
    }

    private static String dataFile(long tabletId, String md5) {
        return tabletId + "_0.dat." + md5;
    }

    private static String headerFile(long tabletId, String md5) {
        return tabletId + ".hdr." + md5;
    }

    private OlapTable copyOf(String tableName, List<String> reservedPartitions) {
        return ((OlapTable) db.getTable(tableName)).selectiveCopy(reservedPartitions, true, IndexExtState.VISIBLE);
    }

    private static BackupMeta roundTrip(BackupMeta meta) throws IOException {
        File file = File.createTempFile("__meta", null, new File(BackupHandler.BACKUP_ROOT_DIR.toString()));
        try {
            meta.writeToFile(file);
            return BackupMeta.fromFile(file.getAbsolutePath(), FeConstants.STARROCKS_META_VERSION);
        } finally {
            file.delete();
        }
    }

    private static void forEachTablet(BackupJobInfo info, Consumer<BackupTabletInfo> action) {
        for (BackupTableInfo table : info.tables.values()) {
            for (BackupPartitionInfo partition : table.partitions.values()) {
                for (BackupPhysicalPartitionInfo physical : partition.subPartitions.values()) {
                    for (BackupIndexInfo index : physical.indexes.values()) {
                        index.tablets.forEach(action);
                    }
                }
            }
        }
    }

    private static void renumberTablets(BackupJobInfo info, Collection<Long> tabletIds) {
        forEachTablet(info, tablet -> {
            if (tabletIds.contains(tablet.id)) {
                tablet.id += 100000;
                tablet.files.replaceAll(e -> new ManifestEntry(e.getFile().replace(
                        String.valueOf(tablet.id - 100000), String.valueOf(tablet.id)), e.getHolder(),
                        e.isHolderIncremental()));
            }
        });
    }

    private static MaterializedIndexMeta indexMeta(OlapTable table, long indexMetaId) {
        return table.getIndexMetaIdToMeta().get(indexMetaId);
    }

    private PhysicalPartition physicalPartition(String tableName, long physicalPartitionId) {
        return ((OlapTable) db.getTable(tableName)).getPhysicalPartition(physicalPartitionId);
    }

    private Set<Long> tablets(String tableName, long... physicalPartitionIds) {
        Set<Long> ids = Sets.newHashSet();
        for (long physicalPartitionId : physicalPartitionIds) {
            for (MaterializedIndex index : physicalPartition(tableName, physicalPartitionId)
                    .getLatestMaterializedIndices(IndexExtState.VISIBLE)) {
                for (Tablet tablet : index.getTablets()) {
                    ids.add(tablet.getId());
                }
            }
        }
        return ids;
    }

    private void makeAllReplicasHealthy() {
        for (String tableName : List.of(TBL2, TBL4)) {
            for (Partition partition : ((OlapTable) db.getTable(tableName)).getPartitions()) {
                for (PhysicalPartition physical : partition.getSubPartitions()) {
                    for (MaterializedIndex index : physical.getLatestMaterializedIndices(IndexExtState.ALL)) {
                        for (Tablet tablet : index.getTablets()) {
                            for (Replica replica : ((LocalTablet) tablet).getImmutableReplicas()) {
                                // CatalogMocker's replicas get schema hash 0, which no index has.
                                Deencapsulation.setField(replica, "version", 1000L);
                                Deencapsulation.setField(replica, "schemaHash", -1);
                            }
                        }
                    }
                }
            }
        }
    }

    // Runs PENDING and returns the tablets that got a snapshot task.
    private Set<Long> snapshotTablets(BackupJob job) {
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.SNAPSHOTING, job.getState());
        return Sets.newHashSet(job.unfinishedTaskIds.keySet());
    }

    private List<UploadTask> uploadTasks(BackupJob job) {
        return uploadTasks(job, id -> List.of(id + "_0.dat", id + ".hdr"));
    }

    // Finishes every snapshot task with the given local file names, then runs the job until it sends upload tasks.
    private List<UploadTask> uploadTasks(BackupJob job, Function<Long, List<String>> localFiles) {
        TBackend backend = new TBackend("", 0, 1);
        for (Map.Entry<Long, Long> entry : Maps.newHashMap(job.unfinishedTaskIds).entrySet()) {
            long tabletId = entry.getKey();
            SnapshotTask task = (SnapshotTask) AgentTaskQueue.getTask(entry.getValue(), TTaskType.MAKE_SNAPSHOT,
                    tabletId);
            TFinishTaskRequest request = new TFinishTaskRequest(backend, TTaskType.MAKE_SNAPSHOT,
                    task.getSignature(), new TStatus(TStatusCode.OK));
            request.setSnapshot_files(Lists.newArrayList(localFiles.apply(tabletId)));
            request.setSnapshot_path(SNAPSHOT_PATH);
            Assertions.assertTrue(job.finishTabletSnapshotTask(task, request));
        }
        job.run();
        Assertions.assertEquals(BackupJobState.UPLOAD_SNAPSHOT, job.getState());
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOADING, job.getState());

        List<UploadTask> tasks = Lists.newArrayList();
        for (Map.Entry<Long, Long> entry : job.unfinishedTaskIds.entrySet()) {
            tasks.add((UploadTask) AgentTaskQueue.getTask(entry.getValue(), TTaskType.UPLOAD, entry.getKey()));
        }
        return tasks;
    }

    private void finishUploads(BackupJob job, List<UploadTask> tasks, Function<Long, List<String>> reportedFiles) {
        for (UploadTask task : tasks) {
            Map<Long, List<String>> tabletFiles = Maps.newHashMap();
            for (String src : task.getSrcToDestPath().keySet()) {
                long tabletId = tabletIdOfSource(src);
                tabletFiles.put(tabletId, Lists.newArrayList(reportedFiles.apply(tabletId)));
            }
            TFinishTaskRequest request = new TFinishTaskRequest(new TBackend("", 0, 1), TTaskType.UPLOAD,
                    task.getSignature(), new TStatus(TStatusCode.OK));
            request.setTablet_files(tabletFiles);
            Assertions.assertTrue(job.finishSnapshotUploadTask(task, request));
        }
    }

    // Read from the request the BE receives.
    private static List<String> reuseList(List<UploadTask> tasks, long tabletId) {
        for (UploadTask task : tasks) {
            for (Map.Entry<String, String> entry : task.getSrcToDestPath().entrySet()) {
                if (tabletIdOfSource(entry.getKey()) == tabletId) {
                    TUploadReq request = task.toThrift();
                    if (!request.isSetReusable_files()) {
                        return List.of();
                    }
                    return request.getReusable_files().getOrDefault(entry.getValue(), List.of());
                }
            }
        }
        throw new AssertionError("no upload for tablet " + tabletId);
    }

    // A snapshot source path is <snapshot path>/<tablet id>/<schema hash>.
    private static long tabletIdOfSource(String src) {
        String[] parts = src.split("/");
        return Long.parseLong(parts[parts.length - 2]);
    }
}
