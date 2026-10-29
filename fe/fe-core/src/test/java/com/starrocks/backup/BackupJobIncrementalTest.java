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
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.qe.ShowResultMetaFactory;
import com.starrocks.qe.ShowResultSetMetaData;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.server.NodeMgr;
import com.starrocks.sql.ast.KeysType;
import com.starrocks.sql.ast.ShowBackupStmt;
import com.starrocks.sql.ast.ShowSnapshotStmt;
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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

// Change detection (test-design.md section 3), the snapshot content it decides (TC-020..045), and the job's
// restart, cancel and timeout (TC-081, TC-087, TC-089), and what SHOW SNAPSHOT and SHOW BACKUP show of it
// (TC-090, TC-092).
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
    // remote paths of the files the FE itself uploads: __meta and __info
    private final List<String> uploadedByFe = Lists.newArrayList();
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

    @AfterAll
    public static void end() throws IOException {
        if (BackupHandler.BACKUP_ROOT_DIR.toFile().exists()) {
            try (var paths = Files.walk(BackupHandler.BACKUP_ROOT_DIR, FileVisitOption.FOLLOW_LINKS)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
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
                uploadedByFe.add(remoteFilePath);
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

    // TC-040
    @Test
    public void incrementalMetadataMatchesAFullSnapshotOfTheSameState() throws Exception {
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        BackupJob full = runBackup(fullJob("full_label", TBL2), DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);
        BackupJob incremental = runBackup(incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { })),
                DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);

        Assertions.assertEquals(readString(full.getLocalMetaInfoFilePath()),
                readString(incremental.getLocalMetaInfoFilePath()));
        JSONObject fullInfo = withoutSnapshotIdentityAndManifests(readJson(full.getLocalJobInfoFilePath()));
        JSONObject incrementalInfo = withoutSnapshotIdentityAndManifests(readJson(incremental.getLocalJobInfoFilePath()));
        Assertions.assertTrue(fullInfo.similar(incrementalInfo), fullInfo + "\n" + incrementalInfo);
    }

    // TC-041: P1 and P5 partitions in one backup, P2 in another.
    @Test
    public void everyTabletHasACompleteManifest() throws Exception {
        BackupJob withP1AndP5 = runBackup(incrementalJob(TBL2,
                        baseOf(TBL2, List.of(CatalogMocker.TEST_PARTITION2_NAME), copy -> { }, info -> { })),
                DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);
        assertCompleteManifests(withP1AndP5, tablets(TBL2, P1, P2));

        AgentTaskQueue.clearAllTasks();
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        BackupJob withP2 = runBackup(incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { })),
                DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);
        assertCompleteManifests(withP2, tablets(TBL2, P1, P2));
    }

    // TC-042: a file unchanged since the full snapshot is still held by it, three incrementals later.
    @Test
    public void reusedFileKeepsPointingAtTheFullSnapshotThroughAChain() throws Exception {
        long tabletId = CatalogMocker.TEST_BASE_TABLET_P1_ID;
        List<ManifestEntry> manifest = runChain().get(tabletId);

        Assertions.assertEquals(new ManifestEntry(tabletId + "_a.dat." + MD5_A, "full", false), manifest.get(0));
    }

    // TC-043: inc3's manifest for a tablet names each holder for its own files.
    @Test
    public void manifestNamesEveryHolderForItsOwnFiles() throws Exception {
        long tabletId = CatalogMocker.TEST_BASE_TABLET_P1_ID;
        List<ManifestEntry> manifest = runChain().get(tabletId);

        Assertions.assertEquals(List.of(
                new ManifestEntry(tabletId + "_a.dat." + MD5_A, "full", false),
                new ManifestEntry(tabletId + "_b.dat." + MD5_B, "inc1", true),
                new ManifestEntry(tabletId + "_c.dat." + MD5_C, "inc2", true),
                new ManifestEntry(tabletId + ".hdr." + MD5_E, "inc2", true)), manifest);
    }

    // TC-044
    @Test
    public void infoRecordsFormatVersionTypeAndBase() throws Exception {
        BackupJob full = runBackup(fullJob("full_label", TBL2), DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);
        AgentTaskQueue.clearAllTasks();
        BackupJob incremental = runBackup(incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { })),
                DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);

        JSONObject fullJson = readJson(full.getLocalJobInfoFilePath());
        Assertions.assertEquals(BackupJobInfo.SNAPSHOT_FORMAT_VERSION, fullJson.getInt("snapshot_format_version"));
        Assertions.assertEquals("full", fullJson.getString("type"));
        Assertions.assertFalse(fullJson.has("base_snapshot"));
        JSONObject incrementalJson = readJson(incremental.getLocalJobInfoFilePath());
        Assertions.assertEquals(BackupJobInfo.SNAPSHOT_FORMAT_VERSION,
                incrementalJson.getInt("snapshot_format_version"));
        Assertions.assertEquals("incremental", incrementalJson.getString("type"));
        Assertions.assertEquals(BASE, incrementalJson.getString("base_snapshot"));

        BackupJobInfo parsed = BackupJobInfo.fromFile(incremental.getLocalJobInfoFilePath());
        Assertions.assertTrue(parsed.isIncremental());
        Assertions.assertEquals(BASE, parsed.baseSnapshot);
        Assertions.assertFalse(BackupJobInfo.fromFile(full.getLocalJobInfoFilePath()).isIncremental());
    }

    // TC-045
    @Test
    public void stockParserStillReadsTheInfo() throws Exception {
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        BackupJob incremental = runBackup(incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { })),
                DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);

        BackupJobInfo stock = StockBackupJobInfoParser.parse(readString(incremental.getLocalJobInfoFilePath()));
        BackupJobInfo current = BackupJobInfo.fromFile(incremental.getLocalJobInfoFilePath());
        Assertions.assertEquals(tabletOrder(current), tabletOrder(stock));
        Assertions.assertEquals(tablets(TBL2, P1, P2), Sets.newHashSet(tabletOrder(stock)));
    }

    // TC-081: the job is logged after the snapshots (UPLOAD_SNAPSHOT) and after the uploads (SAVE_META). A restarted
    // FE resumes from either with only what was logged, and must still write the same manifests.
    @Test
    public void jobResumedFromTheEditLogKeepsDecisionsReuseListsAndManifests() throws Exception {
        IncrementalBase base = baseOf(TBL2, null, copy -> { }, info -> { });
        IncrementalBase sameBase = baseOf(TBL2, null, copy -> { }, info -> { });
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        BackupJob uninterrupted = runBackup(incrementalJob(TBL2, base), DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);

        AgentTaskQueue.clearAllTasks();
        BackupJob job = incrementalJob(TBL2, sameBase);
        snapshotTablets(job);
        finishSnapshots(job, DEFAULT_LOCAL_FILES);
        job = resumeFromEditLog(job);
        List<UploadTask> uploads = sendUploads(job);
        Assertions.assertEquals(tablets(TBL2, P1), uploadedTablets(uploads));
        for (long tabletId : tablets(TBL2, P1)) {
            Assertions.assertEquals(List.of(dataFile(tabletId, MD5_A), headerFile(tabletId, MD5_B)),
                    reuseList(uploads, tabletId));
        }
        finishUploads(job, uploads, DEFAULT_REPORTED_FILES);
        job.run();
        Assertions.assertEquals(BackupJobState.SAVE_META, job.getState());
        job = resumeFromEditLog(job);
        job.run();

        Assertions.assertTrue(job.getStatus().ok(), job.getStatus().toString());
        Assertions.assertEquals(BackupJobState.UPLOAD_INFO, job.getState());
        Assertions.assertEquals(writtenManifests(uninterrupted), writtenManifests(job));
    }

    // TC-087
    @Test
    public void cancelInAnyStateWritesNoInfo() throws Exception {
        for (BackupJobState cancelIn : List.of(BackupJobState.PENDING, BackupJobState.SNAPSHOTING,
                BackupJobState.UPLOAD_SNAPSHOT, BackupJobState.UPLOADING, BackupJobState.SAVE_META,
                BackupJobState.UPLOAD_INFO)) {
            AgentTaskQueue.clearAllTasks();
            BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
            PhysicalPartition p1 = physicalPartition(TBL2, P1);
            p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
            runUntil(job, cancelIn);

            Assertions.assertEquals(Status.OK, job.cancel(), cancelIn.name());
            job.run();

            Assertions.assertEquals(BackupJobState.CANCELLED, job.getState(), cancelIn.name());
            Assertions.assertEquals(List.of(), uploadedByFe, cancelIn.name());
        }
    }

    // TC-089
    @Test
    public void timeoutWhileUploadingCancelsTheJob() throws Exception {
        BackupJob job = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        runUntil(job, BackupJobState.UPLOADING);

        Deencapsulation.setField(job, "timeoutMs", 0L);
        job.run();

        Assertions.assertEquals(BackupJobState.CANCELLED, job.getState());
        Assertions.assertEquals(Status.ErrCode.TIMEOUT, job.getStatus().getErrCode());
        Assertions.assertEquals(List.of(), uploadedByFe);
    }

    // TC-090
    @Test
    public void detailedShowSnapshotShowsTypeAndBase() throws Exception {
        BackupJob full = runBackup(fullJob("full_label", TBL2), DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);
        AgentTaskQueue.clearAllTasks();
        BackupJob incremental = runBackup(incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { })),
                DEFAULT_LOCAL_FILES, DEFAULT_REPORTED_FILES);

        JSONObject fullDetails = showSnapshotDetails(full);
        Assertions.assertEquals("full", fullDetails.getString("type"));
        Assertions.assertFalse(fullDetails.has("base_snapshot"));
        JSONObject incrementalDetails = showSnapshotDetails(incremental);
        Assertions.assertEquals("incremental", incrementalDetails.getString("type"));
        Assertions.assertEquals(BASE, incrementalDetails.getString("base_snapshot"));
    }

    // TC-092
    @Test
    public void showBackupShowsTypeAndBaseDuringAndAfterTheJob() throws Exception {
        BackupJob running = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
        PhysicalPartition p1 = physicalPartition(TBL2, P1);
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        runUntil(running, BackupJobState.UPLOADING);
        assertShownTypeAndBase(running, "incremental", BASE);

        AgentTaskQueue.clearAllTasks();
        BackupJob finished = incrementalJob(TBL2, baseOf(TBL2, null, copy -> { }, info -> { }));
        p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
        runUntil(finished, BackupJobState.UPLOAD_INFO);
        finished.run();
        Assertions.assertEquals(BackupJobState.FINISHED, finished.getState());
        assertShownTypeAndBase(finished, "incremental", BASE);

        AgentTaskQueue.clearAllTasks();
        BackupJob full = fullJob("full_label", TBL2);
        runUntil(full, BackupJobState.UPLOADING);
        assertShownTypeAndBase(full, "full", FeConstants.NULL_STRING);
    }

    // ---- helpers ----

    private static final Function<Long, List<String>> DEFAULT_LOCAL_FILES = id -> List.of(id + "_0.dat", id + ".hdr");
    private static final Function<Long, List<String>> DEFAULT_REPORTED_FILES =
            id -> List.of(dataFile(id, MD5_A), headerFile(id, MD5_C));

    private BackupJob incrementalJob(String tableName, IncrementalBase base) {
        return incrementalJob(LABEL, tableName, base);
    }

    private BackupJob incrementalJob(String label, String tableName, IncrementalBase base) {
        BackupJob job = fullJob(label, tableName);
        job.setIncrementalBase(base);
        return job;
    }

    private BackupJob fullJob(String label, String tableName) {
        return new BackupJob(label, CatalogMocker.TEST_DB_ID, CatalogMocker.TEST_DB_NAME,
                tableRefs(tableName), 13600 * 1000, globalStateMgr, REPO_ID);
    }

    // Runs a backup until its __meta and __info are written locally (state UPLOAD_INFO).
    private BackupJob runBackup(BackupJob job, Function<Long, List<String>> localFiles,
                                Function<Long, List<String>> reportedFiles) {
        // snapshot task signatures are tablet ids, so another job's tasks would be found instead
        AgentTaskQueue.clearAllTasks();
        snapshotTablets(job);
        finishUploads(job, uploadTasks(job, localFiles), reportedFiles);
        job.run();
        Assertions.assertEquals(BackupJobState.SAVE_META, job.getState());
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(BackupJobState.UPLOAD_INFO, job.getState());
        return job;
    }

    // As the next incremental reads this snapshot from the repository.
    private static IncrementalBase baseFromWrittenFiles(BackupJob job) throws IOException {
        return IncrementalBase.of(BackupJobInfo.fromFile(job.getLocalJobInfoFilePath()),
                BackupMeta.fromFile(job.getLocalMetaInfoFilePath(), FeConstants.STARROCKS_META_VERSION));
    }

    // full -> inc1 -> inc2 -> inc3, with p1 changed every time; returns inc3's written manifests. Tablet 60001
    // gets a new data file in inc1 and in inc2, and a new header in each snapshot except inc3.
    private Map<Long, List<ManifestEntry>> runChain() throws Exception {
        long tabletId = CatalogMocker.TEST_BASE_TABLET_P1_ID;
        List<List<String>> chain = List.of(
                List.of(tabletId + "_a.dat." + MD5_A, tabletId + ".hdr." + MD5_B),
                List.of(tabletId + "_a.dat." + MD5_A, tabletId + "_b.dat." + MD5_B, tabletId + ".hdr." + MD5_C),
                List.of(tabletId + "_a.dat." + MD5_A, tabletId + "_b.dat." + MD5_B, tabletId + "_c.dat." + MD5_C,
                        tabletId + ".hdr." + MD5_E),
                List.of(tabletId + "_a.dat." + MD5_A, tabletId + "_b.dat." + MD5_B, tabletId + "_c.dat." + MD5_C,
                        tabletId + ".hdr." + MD5_E));
        List<String> labels = List.of("full", "inc1", "inc2", "inc3");
        PhysicalPartition p1 = physicalPartition(TBL2, P1);

        BackupJob previous = null;
        for (int i = 0; i < chain.size(); i++) {
            AgentTaskQueue.clearAllTasks();
            p1.setVisibleVersion(p1.getVisibleVersion() + 1, p1.getVisibleVersionTime() + 1);
            List<String> files = chain.get(i);
            Function<Long, List<String>> reported = id -> id == tabletId ? files : DEFAULT_REPORTED_FILES.apply(id);
            Function<Long, List<String>> local = id -> reported.apply(id).stream()
                    .map(f -> Repository.decodeFileNameWithChecksum(f).first).toList();
            BackupJob job = previous == null ? fullJob(labels.get(i), TBL2)
                    : incrementalJob(labels.get(i), TBL2, baseFromWrittenFiles(previous));
            previous = runBackup(job, local, reported);
        }
        return writtenManifests(previous);
    }

    private void assertCompleteManifests(BackupJob job, Set<Long> expectedTablets) throws IOException {
        Map<Long, List<ManifestEntry>> written = writtenManifests(job);
        Assertions.assertEquals(expectedTablets, written.keySet());
        for (Map.Entry<Long, List<ManifestEntry>> entry : written.entrySet()) {
            Assertions.assertEquals(2, entry.getValue().size(), "tablet " + entry.getKey());
            for (ManifestEntry file : entry.getValue()) {
                Assertions.assertNotNull(Repository.decodeFileNameWithChecksum(file.getFile()), file.getFile());
                Assertions.assertFalse(file.getHolder().isEmpty(), file.getFile());
            }
        }
    }

    private static List<Long> tabletOrder(BackupJobInfo info) {
        List<Long> ids = Lists.newArrayList();
        forEachTablet(info, tablet -> ids.add(tablet.id));
        return ids;
    }

    private static String readString(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static JSONObject readJson(String path) throws IOException {
        return new JSONObject(readString(path));
    }

    // Drops what legitimately differs between two snapshots of the same state: the label and time, the new
    // keys, and the manifests.
    private static JSONObject withoutSnapshotIdentityAndManifests(JSONObject info) {
        for (String key : List.of("name", "backup_time", "snapshot_format_version", "type", "base_snapshot")) {
            info.remove(key);
        }
        clearTabletFiles(info);
        return info;
    }

    private static void clearTabletFiles(Object node) {
        if (node instanceof JSONObject object) {
            for (String key : object.keySet()) {
                if (key.equals("tablets")) {
                    JSONObject tablets = object.getJSONObject(key);
                    for (String tabletId : tablets.keySet()) {
                        tablets.put(tabletId, new JSONArray());
                    }
                } else {
                    clearTabletFiles(object.get(key));
                }
            }
        } else if (node instanceof JSONArray array) {
            array.forEach(BackupJobIncrementalTest::clearTabletFiles);
        }
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
        finishSnapshots(job, localFiles);
        return sendUploads(job);
    }

    // Finishes every snapshot task with the given local file names, then runs the job into UPLOAD_SNAPSHOT.
    private void finishSnapshots(BackupJob job, Function<Long, List<String>> localFiles) {
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
    }

    // Runs UPLOAD_SNAPSHOT and returns the upload tasks it sends.
    private List<UploadTask> sendUploads(BackupJob job) {
        job.run();
        // not Status.OK itself: a job read back from the edit log has its own OK instance
        Assertions.assertTrue(job.getStatus().ok(), job.getStatus().toString());
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

    private void runUntil(BackupJob job, BackupJobState target) {
        if (target == BackupJobState.PENDING) {
            return;
        }
        snapshotTablets(job);
        if (target == BackupJobState.SNAPSHOTING) {
            return;
        }
        finishSnapshots(job, DEFAULT_LOCAL_FILES);
        if (target == BackupJobState.UPLOAD_SNAPSHOT) {
            return;
        }
        List<UploadTask> uploads = sendUploads(job);
        if (target == BackupJobState.UPLOADING) {
            return;
        }
        finishUploads(job, uploads, DEFAULT_REPORTED_FILES);
        job.run();
        Assertions.assertEquals(BackupJobState.SAVE_META, job.getState());
        if (target == BackupJobState.SAVE_META) {
            return;
        }
        job.run();
        Assertions.assertEquals(BackupJobState.UPLOAD_INFO, job.getState());
    }

    // What a restarted FE gets back: the job as it was last written to the edit log.
    private BackupJob resumeFromEditLog(BackupJob job) {
        String json = GsonUtils.GSON.toJson(job, AbstractJob.class);
        BackupJob resumed = (BackupJob) GsonUtils.GSON.fromJson(json, AbstractJob.class);
        resumed.setGlobalStateMgr(globalStateMgr);
        return resumed;
    }

    private static Set<Long> uploadedTablets(List<UploadTask> tasks) {
        Set<Long> ids = Sets.newHashSet();
        for (UploadTask task : tasks) {
            task.getSrcToDestPath().keySet().forEach(src -> ids.add(tabletIdOfSource(src)));
        }
        return ids;
    }

    // SHOW BACKUP shows getInfo() by position, under the columns the statement's metadata names.
    private static void assertShownTypeAndBase(BackupJob job, String type, String base) {
        ShowResultSetMetaData columns =
                new ShowResultMetaFactory().getMetadata(new ShowBackupStmt(CatalogMocker.TEST_DB_NAME));
        List<String> names = Lists.newArrayList();
        for (int i = 0; i < columns.getColumnCount(); i++) {
            names.add(columns.getColumn(i).getName());
        }
        List<String> row = job.getInfo();
        Assertions.assertEquals(names.size(), row.size(), names + "\n" + row);
        Assertions.assertEquals(type, row.get(names.indexOf("Type")), row.toString());
        Assertions.assertEquals(base, row.get(names.indexOf("BaseSnapshot")), row.toString());
    }

    // The Details column of SHOW SNAPSHOT ... WHERE SNAPSHOT = ... AND TIMESTAMP = ..., with the repository serving
    // the job's own __info.
    private JSONObject showSnapshotDetails(BackupJob job) {
        new MockUp<Repository>() {
            @Mock
            Status download(String remoteFilePath, String localFilePath) throws IOException {
                Files.copy(Path.of(job.getLocalJobInfoFilePath()), Path.of(localFilePath),
                        StandardCopyOption.REPLACE_EXISTING);
                return Status.OK;
            }
        };
        List<String> row = repo.getSnapshotInfos(job.getLabel(), "2026-10-29-00-00-00-000", null).get(0);
        Assertions.assertEquals("OK", row.get(ShowSnapshotStmt.SNAPSHOT_DETAIL.indexOf("Status")), row.toString());
        return new JSONObject(row.get(ShowSnapshotStmt.SNAPSHOT_DETAIL.indexOf("Details")));
    }

    private static Map<Long, List<ManifestEntry>> writtenManifests(BackupJob job) throws IOException {
        Map<Long, List<ManifestEntry>> manifests = Maps.newHashMap();
        forEachTablet(BackupJobInfo.fromFile(job.getLocalJobInfoFilePath()),
                tablet -> manifests.put(tablet.id, tablet.files));
        return manifests;
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
