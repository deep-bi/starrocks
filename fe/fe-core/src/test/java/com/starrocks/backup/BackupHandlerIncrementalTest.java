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
import com.starrocks.catalog.BrokerMgr;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.metric.LongCounterMetric;
import com.starrocks.metric.Metric;
import com.starrocks.metric.MetricRepo;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.sql.ast.BackupStmt;
import com.starrocks.sql.ast.BackupStmt.BackupType;
import com.starrocks.sql.ast.CreateRepositoryStmt;
import com.starrocks.sql.ast.LabelName;
import com.starrocks.sql.ast.QualifiedName;
import com.starrocks.sql.ast.TableRef;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

public class BackupHandlerIncrementalTest {
    private static final String REPO = "repo";
    private static final String BASE = "base";
    private static final String FORMAT_VERSION_KEY = "snapshot_format_version";

    private final String tmpPath = "./tmp" + System.currentTimeMillis();
    @Mocked
    private BrokerMgr brokerMgr;
    private BackupHandler handler;
    private Database db;

    // What the mocked repository holds: snapshot names, and the base's __info and __meta (null when missing).
    private List<String> repoSnapshots;
    private BackupJobInfo baseInfo;
    private BackupMeta baseMeta;

    @BeforeEach
    public void setUp() throws Exception {
        UtFrameUtils.setUpForPersistTest();
        Config.tmp_dir = tmpPath;
        new File(Config.tmp_dir).mkdirs();
        MetricRepo.COUNTER_UNFINISHED_BACKUP_JOB = new LongCounterMetric("unfinished_backup_job",
                Metric.MetricUnit.REQUESTS, "current unfinished backup job");
        MetricRepo.COUNTER_UNFINISHED_RESTORE_JOB = new LongCounterMetric("unfinished_restore_job",
                Metric.MetricUnit.REQUESTS, "current unfinished restore job");
        db = CatalogMocker.mockDb();
        repoSnapshots = Lists.newArrayList(BASE);
        baseInfo = null;
        baseMeta = new BackupMeta(List.of(db.getTable(CatalogMocker.TEST_TBL_NAME)));

        new MockUp<Repository>() {
            @Mock
            public Status initRepository() {
                return Status.OK;
            }

            @Mock
            public Status listSnapshots(List<String> snapshotNames) {
                snapshotNames.addAll(repoSnapshots);
                return Status.OK;
            }

            @Mock
            public Status getSnapshotInfoFile(String label, List<BackupJobInfo> infos) {
                if (baseInfo == null || !baseInfo.name.equals(label)) {
                    return new Status(Status.ErrCode.NOT_FOUND, "no __info file for snapshot " + label);
                }
                infos.add(baseInfo);
                return Status.OK;
            }

            @Mock
            public Status getSnapshotMetaFile(String label, List<BackupMeta> backupMetas, int metaVersion,
                                              int starrocksMetaVersion) {
                if (baseMeta == null) {
                    return new Status(Status.ErrCode.NOT_FOUND, "no __meta file for snapshot " + label);
                }
                backupMetas.add(baseMeta);
                return Status.OK;
            }
        };

        new MockUp<LocalMetastore>() {
            final Database database = db;

            @Mock
            public Database getDb(String dbName) {
                return database;
            }

            @Mock
            public Table getTable(String dbName, String tblName) {
                return database.getTable(tblName);
            }
        };

        new Expectations() {
            {
                brokerMgr.containsBroker(anyString);
                minTimes = 0;
                result = true;
            }
        };
        GlobalStateMgr.getCurrentState().getNodeMgr().setBrokerMgr(brokerMgr);

        handler = new BackupHandler(GlobalStateMgr.getCurrentState());
        handler.createRepository(new CreateRepositoryStmt(false, REPO, "broker", "bos://location",
                Maps.newHashMap()));
    }

    @AfterEach
    public void tearDown() throws Exception {
        Files.walk(Paths.get(tmpPath), FileVisitOption.FOLLOW_LINKS).sorted(Comparator.reverseOrder())
                .map(Path::toFile).forEach(File::delete);
        UtFrameUtils.tearDownForPersisTest();
    }

    // TC-002 (submit part): an incremental on a complete base of the current format creates a job.
    @Test
    public void incrementalOnCompleteBaseCreatesJob() throws Exception {
        baseInfo = baseInfoWith(CatalogMocker.TEST_DB_NAME, 1);

        handler.process(new ConnectContext(), incremental("inc1", BASE));

        Assertions.assertTrue(handler.getJob(CatalogMocker.TEST_DB_ID) instanceof BackupJob);
    }

    // TC-006
    @Test
    public void baseMissingFromRepositoryIsRejected() {
        assertRejected(incremental("inc1", "missing"),
                "Base snapshot 'missing' does not exist in repository '" + REPO + "'");
    }

    // TC-007
    @Test
    public void baseWithoutInfoFileIsRejected() {
        assertRejected(incremental("inc1", BASE),
                "Base snapshot '" + BASE + "' in repository '" + REPO + "' is not complete");
    }

    // TC-007b
    @Test
    public void baseWithoutMetaFileIsRejected() throws Exception {
        baseInfo = baseInfoWith(CatalogMocker.TEST_DB_NAME, 1);
        baseMeta = null;

        assertRejected(incremental("inc1", BASE),
                "Base snapshot '" + BASE + "' in repository '" + REPO + "' is not complete");
    }

    // TC-008
    @Test
    public void baseFromAnotherDatabaseIsRejected() throws Exception {
        baseInfo = baseInfoWith("other", 1);

        assertRejected(incremental("inc1", BASE),
                "Base snapshot '" + BASE + "' was taken from database 'other', not '" +
                        CatalogMocker.TEST_DB_NAME + "'");
    }

    // TC-009: this FE writes format version 1, so 2 is from a newer FE.
    @Test
    public void baseWithUnknownFormatVersionIsRejected() throws Exception {
        baseInfo = baseInfoWith(CatalogMocker.TEST_DB_NAME, 2);

        assertRejected(incremental("inc1", BASE),
                "Base snapshot '" + BASE + "' has snapshot format version 2, which this FE does not support");
    }

    // TC-010
    @Test
    public void legacyBaseWithoutFormatVersionIsAccepted() throws Exception {
        baseInfo = baseInfoWith(CatalogMocker.TEST_DB_NAME, null);

        handler.process(new ConnectContext(), incremental("inc1", BASE));

        Assertions.assertTrue(handler.getJob(CatalogMocker.TEST_DB_ID) instanceof BackupJob);
    }

    // TC-011
    @Test
    public void existingLabelIsRejectedForBothTypes() throws Exception {
        baseInfo = baseInfoWith(CatalogMocker.TEST_DB_NAME, 1);
        repoSnapshots.add("label1");
        String alreadyExists = "Snapshot with name 'label1' already exist in repository";

        assertRejected(full("label1"), alreadyExists);
        assertRejected(incremental("label1", BASE), alreadyExists);
    }

    // TC-012
    @Test
    public void incrementalWhileAnotherJobRunsIsRejected() throws Exception {
        baseInfo = baseInfoWith(CatalogMocker.TEST_DB_NAME, 1);
        handler.process(new ConnectContext(), full("running"));

        assertRejected(incremental("inc1", BASE),
                "Can only run one backup or restore job of a database at same time");
    }

    private void assertRejected(BackupStmt stmt, String expectedMessage) {
        DdlException e = Assertions.assertThrows(DdlException.class,
                () -> handler.process(new ConnectContext(), stmt));
        Assertions.assertTrue(e.getMessage().contains(expectedMessage),
                "expected '" + expectedMessage + "' in: " + e.getMessage());
        AbstractJob job = handler.getJob(CatalogMocker.TEST_DB_ID);
        Assertions.assertFalse(job != null && job.getLabel().equals(stmt.getLabel()), "no job may be created");
    }

    private BackupStmt full(String label) {
        List<TableRef> tblRefs = Lists.newArrayList(new TableRef(
                QualifiedName.of(List.of(CatalogMocker.TEST_DB_NAME, CatalogMocker.TEST_TBL_NAME)), null, null));
        return new BackupStmt(new LabelName(CatalogMocker.TEST_DB_NAME, label), REPO, tblRefs,
                Lists.newArrayList(), null, null, false, "", null, NodePosition.ZERO);
    }

    private BackupStmt incremental(String label, String baseSnapshot) {
        BackupStmt stmt = full(label);
        stmt.setType(BackupType.INCREMENTAL);
        stmt.setBaseSnapshot(baseSnapshot);
        return stmt;
    }

    // Goes through the real __info file format, so the format version key is parsed as an FE would read it.
    private BackupJobInfo baseInfoWith(String dbName, Integer formatVersion) throws IOException {
        OlapTable tbl = (OlapTable) db.getTable(CatalogMocker.TEST_TBL_NAME);
        BackupJobInfo info = BackupJobInfo.fromCatalog(System.currentTimeMillis(), BASE, dbName,
                CatalogMocker.TEST_DB_ID, List.of(tbl), Maps.newHashMap());
        File file = new File(tmpPath, "__info_" + System.nanoTime());
        info.writeToFile(file);

        JSONObject json = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        json.remove(FORMAT_VERSION_KEY);
        if (formatVersion != null) {
            json.put(FORMAT_VERSION_KEY, formatVersion);
        }
        Files.writeString(file.toPath(), json.toString(), StandardCharsets.UTF_8);
        return BackupJobInfo.fromFile(file.getAbsolutePath());
    }
}
