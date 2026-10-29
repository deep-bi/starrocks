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
import com.google.common.collect.Multimap;
import com.starrocks.backup.BackupJobInfo.BackupIndexInfo;
import com.starrocks.backup.BackupJobInfo.BackupPartitionInfo;
import com.starrocks.backup.BackupJobInfo.BackupTableInfo;
import com.starrocks.backup.BackupJobInfo.BackupTabletInfo;
import com.starrocks.backup.RestoreJob.RestoreJobState;
import com.starrocks.backup.mv.MvRestoreContext;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.FakeEditLog;
import com.starrocks.catalog.FsBroker;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Tablet;
import com.starrocks.common.Config;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.common.util.concurrent.MarkedCountDownLatch;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.EditLog;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.system.SystemInfoService;
import com.starrocks.task.AgentTask;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.task.DownloadTask;
import com.starrocks.task.SnapshotTask;
import com.starrocks.thrift.TBackend;
import com.starrocks.thrift.TDownloadReq;
import com.starrocks.thrift.TFinishTaskRequest;
import com.starrocks.thrift.TStatus;
import com.starrocks.thrift.TStatusCode;
import com.starrocks.thrift.TTaskType;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

// The restore side of incremental snapshots (TC-074, TC-075). Same setup as RestoreJobTest.testRunBackupRangeTable:
// test_tbl2 is dropped and restored from a mocked repository.
public class RestoreJobIncrementalTest {
    private static final String LABEL = "inc";
    private static final String REPO_ROOT = "bos://my_repo/__starrocks_repository_repo/";

    private final AtomicLong id = new AtomicLong(50000);
    private final long repoId = 20000;
    private Database db;
    private GlobalStateMgr globalStateMgr;
    private RepositoryMgr repoMgr;
    private BackupMeta backupMeta;
    private boolean oldEnableMetricCalculator;

    @Mocked
    private EditLog editLog;

    // Not @Injectable: the test needs the real path methods; only getBrokerAddress is recorded.
    private final Repository repo = new Repository(repoId, "repo", false, "bos://my_repo",
            new BlobStorage("broker", Maps.newHashMap()));

    private final class MockBackupHandler extends BackupHandler {
        MockBackupHandler(GlobalStateMgr globalStateMgr) {
            super(globalStateMgr);
        }

        @Override
        public RepositoryMgr getRepoMgr() {
            return repoMgr;
        }
    }

    private final class MockRepositoryMgr extends RepositoryMgr {
        @Override
        public Repository getRepo(long repoId) {
            return repo;
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        oldEnableMetricCalculator = Config.enable_metric_calculator;
        Config.enable_metric_calculator = false;
        globalStateMgr = Deencapsulation.newInstance(GlobalStateMgr.class);
        new FakeEditLog();
        db = CatalogMocker.mockDb();
        repoMgr = new MockRepositoryMgr();
        Deencapsulation.setField(globalStateMgr, "backupHandler", new MockBackupHandler(globalStateMgr));
        MetricRepo.init();

        new Expectations(globalStateMgr) {
            {
                globalStateMgr.getEditLog();
                minTimes = 0;
                result = editLog;

                globalStateMgr.isSafeMode();
                minTimes = 0;

                globalStateMgr.isReady();
                minTimes = 0;
            }
        };

        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                minTimes = 0;
                result = globalStateMgr;

                GlobalStateMgr.getServingState();
                minTimes = 0;
                result = globalStateMgr;
            }
        };

        AgentTaskQueue.clearAllTasks();
    }

    @AfterEach
    public void tearDown() {
        Config.enable_metric_calculator = oldEnableMetricCalculator;
    }

    // TC-074, incremental: each tablet's files come from their holders; the legacy path stays empty.
    @Test
    public void incrementalDownloadTasksCarryTheManifestResolvedToHolders() {
        List<DownloadTask> tasks = downloadTasks("full", tabletId -> List.of(
                new ManifestEntry(tabletId + ".hdr.11111111111111111111111111111111", LABEL, true),
                new ManifestEntry(tabletId + "_0.dat.22222222222222222222222222222222", "full", false),
                new ManifestEntry(tabletId + "_1.dat.33333333333333333333333333333333", "inc1", true)));

        int tablets = 0;
        for (DownloadTask task : tasks) {
            TDownloadReq request = task.toThrift();
            for (Map.Entry<String, String> entry : task.getSrcToDestPath().entrySet()) {
                String legacyPrefix = REPO_ROOT + "__ss_" + LABEL + "/__ss_content/";
                Assertions.assertTrue(entry.getKey().startsWith(legacyPrefix), entry.getKey());
                String tabletPath = entry.getKey().substring(legacyPrefix.length());
                long tabletId = Long.parseLong(tabletPath.substring(tabletPath.lastIndexOf("__") + 2));

                Assertions.assertTrue(request.isSetSource_files());
                Assertions.assertEquals(List.of(
                        REPO_ROOT + "__ss_" + LABEL + "/__ss_content_inc/" + tabletPath + "/" + tabletId
                                + ".hdr.11111111111111111111111111111111",
                        REPO_ROOT + "__ss_full/__ss_content/" + tabletPath + "/" + tabletId
                                + "_0.dat.22222222222222222222222222222222",
                        REPO_ROOT + "__ss_inc1/__ss_content_inc/" + tabletPath + "/" + tabletId
                                + "_1.dat.33333333333333333333333333333333"),
                        request.getSource_files().get(entry.getValue()));
                tablets++;
            }
        }
        Assertions.assertTrue(tablets > 0);
    }

    // TC-074, full: the download tasks are exactly as before.
    @Test
    public void fullDownloadTasksSendNoManifestFiles() {
        List<DownloadTask> tasks = downloadTasks(null, tabletId -> List.of(
                new ManifestEntry(tabletId + "_0.dat.22222222222222222222222222222222", LABEL, false)));

        Assertions.assertFalse(tasks.isEmpty());
        for (DownloadTask task : tasks) {
            Assertions.assertFalse(task.toThrift().isSetSource_files());
            for (String src : task.getSrcToDestPath().keySet()) {
                Assertions.assertTrue(src.startsWith(REPO_ROOT + "__ss_" + LABEL + "/__ss_content/"), src);
            }
        }
    }

    // TC-075: a manifest file missing from its holder won't appear by retrying, so the BE's NOT_FOUND cancels an
    // incremental restore. Every other failure is retried as before (REQ-RS-04, REQ-CP-01).
    @Test
    public void missingManifestFileCancelsAnIncrementalRestore() {
        RestoreJob job = downloadingJob("full", tabletId -> List.of(
                new ManifestEntry(tabletId + "_0.dat.22222222222222222222222222222222", "full", false)));
        String missing = "file 1_0.dat.22222222222222222222222222222222 of the snapshot's manifest is missing from "
                + REPO_ROOT + "__ss_full/__ss_content/__db_1/__tbl_2/__part_3/__idx_4/__1";

        failDownload(job, queuedDownloadTasks().get(0), TStatusCode.NOT_FOUND, missing);
        job.run();

        Assertions.assertEquals(RestoreJobState.CANCELLED, job.getState());
        Assertions.assertTrue(job.getStatus().getErrMsg().contains(missing), job.getStatus().toString());
    }

    // TC-075
    @Test
    public void otherDownloadFailureOfAnIncrementalRestoreIsRetried() {
        RestoreJob job = downloadingJob("full", tabletId -> List.of(
                new ManifestEntry(tabletId + "_0.dat.22222222222222222222222222222222", "full", false)));

        failDownload(job, queuedDownloadTasks().get(0), TStatusCode.RUNTIME_ERROR, "get nothing from remote path");
        job.run();

        Assertions.assertEquals(RestoreJobState.DOWNLOADING, job.getState());
        Assertions.assertTrue(job.getStatus().ok(), job.getStatus().toString());
    }

    // TC-075
    @Test
    public void notFoundOnAFullRestoreIsRetriedAsBefore() {
        RestoreJob job = downloadingJob(null, tabletId -> List.of());

        failDownload(job, queuedDownloadTasks().get(0), TStatusCode.NOT_FOUND, "not found");
        job.run();

        Assertions.assertEquals(RestoreJobState.DOWNLOADING, job.getState());
        Assertions.assertTrue(job.getStatus().ok(), job.getStatus().toString());
    }

    private static void failDownload(RestoreJob job, DownloadTask task, TStatusCode code, String message) {
        TStatus status = new TStatus(code);
        status.setError_msgs(List.of(message));
        TFinishTaskRequest request = new TFinishTaskRequest(new TBackend("", 0, 1), TTaskType.DOWNLOAD,
                task.getSignature(), status);
        Assertions.assertFalse(job.finishTabletDownloadTask(task, request));
    }

    private List<DownloadTask> downloadTasks(String baseSnapshot, Function<Long, List<ManifestEntry>> manifest) {
        downloadingJob(baseSnapshot, manifest);
        return queuedDownloadTasks();
    }

    private static List<DownloadTask> queuedDownloadTasks() {
        List<DownloadTask> tasks = Lists.newArrayList();
        for (long beId : List.of(CatalogMocker.BACKEND1_ID, CatalogMocker.BACKEND2_ID, CatalogMocker.BACKEND3_ID)) {
            for (AgentTask task : AgentTaskQueue.getDiffTasks(beId, Maps.newHashMap())) {
                if (task instanceof DownloadTask) {
                    tasks.add((DownloadTask) task);
                }
            }
        }
        Assertions.assertFalse(tasks.isEmpty());
        return tasks;
    }

    // Restores test_tbl2 from a snapshot labelled LABEL (incremental when baseSnapshot is set) and runs the job
    // until it sends its download tasks.
    private RestoreJob downloadingJob(String baseSnapshot, Function<Long, List<ManifestEntry>> manifest) {
        SystemInfoService systemInfoService = new SystemInfoService();
        new Expectations(globalStateMgr) {
            {
                globalStateMgr.getLocalMetastore().getDb(anyLong);
                minTimes = 0;
                result = db;

                globalStateMgr.getNextId();
                minTimes = 0;
                result = new Delegate<Long>() {
                    public Long getNextId() {
                        return id.incrementAndGet();
                    }
                };

                globalStateMgr.getNodeMgr().getClusterInfo();
                minTimes = 0;
                result = systemInfoService;
            }
        };
        List<Long> beIds = Lists.newArrayList(CatalogMocker.BACKEND1_ID, CatalogMocker.BACKEND2_ID,
                CatalogMocker.BACKEND3_ID);
        new Expectations(systemInfoService) {
            {
                systemInfoService.getNodeSelector().seqChooseBackendIds(anyInt, anyBoolean, anyBoolean, null);
                minTimes = 0;
                result = beIds;

                systemInfoService.getBackend(anyLong);
                minTimes = 0;
                result = null;

                systemInfoService.getComputeNode(anyLong);
                minTimes = 0;
                result = null;

                systemInfoService.checkExceedDiskCapacityLimit((Multimap<Long, Long>) any, anyBoolean);
                minTimes = 0;
                result = com.starrocks.common.Status.OK;
            }
        };
        new Expectations(repo) {
            {
                repo.getBrokerAddress(anyLong, (GlobalStateMgr) any, (List) any);
                minTimes = 0;
                result = new Delegate() {
                    public Status getBrokerAddress(Long beId, GlobalStateMgr mgr, List<FsBroker> brokers) {
                        brokers.add(new FsBroker());
                        return Status.OK;
                    }
                };
            }
        };
        new MockUp<MarkedCountDownLatch>() {
            @Mock
            boolean await(long timeout, TimeUnit unit) {
                return true;
            }
        };

        BackupJobInfo jobInfo = new BackupJobInfo();
        jobInfo.backupTime = System.currentTimeMillis();
        jobInfo.dbId = CatalogMocker.TEST_DB_ID;
        jobInfo.dbName = CatalogMocker.TEST_DB_NAME;
        jobInfo.name = LABEL;
        jobInfo.success = true;
        jobInfo.snapshotFormatVersion = BackupJobInfo.SNAPSHOT_FORMAT_VERSION;
        jobInfo.baseSnapshot = baseSnapshot;

        OlapTable restoredTable = (OlapTable) db.getTable(CatalogMocker.TEST_TBL2_ID);
        BackupTableInfo tblInfo = new BackupTableInfo();
        tblInfo.id = CatalogMocker.TEST_TBL2_ID;
        tblInfo.name = CatalogMocker.TEST_TBL2_NAME;
        jobInfo.tables.put(tblInfo.name, tblInfo);
        for (Partition partition : restoredTable.getPartitions()) {
            BackupPartitionInfo partInfo = new BackupPartitionInfo();
            partInfo.id = partition.getId();
            partInfo.name = partition.getName();
            tblInfo.partitions.put(partInfo.name, partInfo);
            for (MaterializedIndex index : partition.getDefaultPhysicalPartition()
                    .getLatestMaterializedIndices(IndexExtState.VISIBLE)) {
                BackupIndexInfo idxInfo = new BackupIndexInfo();
                idxInfo.id = index.getId();
                idxInfo.name = restoredTable.getIndexNameByMetaId(index.getMetaId());
                idxInfo.schemaHash = restoredTable.getSchemaHashByIndexMetaId(index.getMetaId());
                partInfo.indexes.put(idxInfo.name, idxInfo);
                for (Tablet tablet : index.getTablets()) {
                    BackupTabletInfo tabletInfo = new BackupTabletInfo();
                    tabletInfo.id = tablet.getId();
                    tabletInfo.files = manifest.apply(tablet.getId());
                    idxInfo.tablets.add(tabletInfo);
                }
            }
        }

        db.dropTable(restoredTable.getName());
        new MockUp<LocalMetastore>() {
            @Mock
            public Database getDb(String dbName) {
                return db;
            }

            @Mock
            public Table getTable(String dbName, String tblName) {
                return db.getTable(tblName);
            }

            @Mock
            public Table getTable(Long dbId, Long tableId) {
                return db.getTable(tableId);
            }
        };

        backupMeta = new BackupMeta(Lists.newArrayList(restoredTable));
        RestoreJob job = new RestoreJob(LABEL, "2018-01-01 01:01:01", db.getId(), db.getFullName(),
                jobInfo, false, 3, 100000, globalStateMgr, repo.getId(), backupMeta, new MvRestoreContext());
        job.setRepo(repo);

        job.run();
        Assertions.assertEquals(RestoreJobState.SNAPSHOTING, job.getState());
        job.run();
        List<AgentTask> snapshotTasks = Lists.newArrayList();
        for (long beId : beIds) {
            snapshotTasks.addAll(AgentTaskQueue.getDiffTasks(beId, Maps.newHashMap()));
        }
        for (AgentTask agentTask : snapshotTasks) {
            if (agentTask.getTaskType() != TTaskType.MAKE_SNAPSHOT) {
                continue;
            }
            SnapshotTask task = (SnapshotTask) agentTask;
            TFinishTaskRequest request = new TFinishTaskRequest(new TBackend("", 0, 1), TTaskType.MAKE_SNAPSHOT,
                    task.getSignature(), new TStatus(TStatusCode.OK));
            request.setSnapshot_path("/path/to/snapshot/" + task.getSignature());
            Assertions.assertTrue(job.finishTabletSnapshotTask(task, request));
        }
        job.run();
        Assertions.assertEquals(RestoreJobState.DOWNLOAD, job.getState());

        AgentTaskQueue.clearAllTasks();
        job.run();
        Assertions.assertEquals(Status.OK, job.getStatus());
        Assertions.assertEquals(RestoreJobState.DOWNLOADING, job.getState());
        return job;
    }
}
