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

package com.starrocks.sql.analyzer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeFail;
import static com.starrocks.sql.analyzer.AnalyzeTestUtil.analyzeSuccess;

public class AnalyzeIncrementalBackupTest {

    private static final String MISSING_BASE = "Incremental backup requires a non-empty 'base_snapshot' property";
    private static final String BASE_WITHOUT_INCREMENTAL =
            "'base_snapshot' is only valid with \"type\" = \"incremental\"";
    private static final String EXTERNAL_CATALOG = "Incremental backup is not supported for external catalogs";

    @BeforeAll
    public static void beforeClass() throws Exception {
        AnalyzeBackupRestoreTest.beforeClass();
    }

    @Test
    public void fullBackupIsUnchangedWhenTypeIsAbsentOrFull() {
        analyzeSuccess("BACKUP DATABASE test SNAPSHOT s1 TO `repo` ON (t0)");
        analyzeSuccess("BACKUP DATABASE test SNAPSHOT s1 TO `repo` ON (t0) PROPERTIES (\"type\" = \"full\")");
    }

    @Test
    public void incrementalWithBaseSnapshotIsAccepted() {
        analyzeSuccess("BACKUP DATABASE test SNAPSHOT s2 TO `repo` ON (t0) " +
                "PROPERTIES (\"type\" = \"incremental\", \"base_snapshot\" = \"s1\")");
        analyzeSuccess("BACKUP DATABASE test SNAPSHOT s2 TO `repo` " +
                "PROPERTIES (\"type\" = \"Incremental\", \"base_snapshot\" = \"s1\")");
    }

    @Test
    public void unknownBackupTypeIsRejected() {
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` PROPERTIES (\"type\" = \"\")",
                "Invalid backup job type");
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` PROPERTIES (\"type\" = \"diff\")",
                "Invalid backup job type");
    }

    @Test
    public void incrementalWithoutBaseSnapshotIsRejectedInsteadOfRunningAFullBackup() {
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` PROPERTIES (\"type\" = \"incremental\")",
                MISSING_BASE);
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` " +
                "PROPERTIES (\"type\" = \"incremental\", \"base_snapshot\" = \"\")", MISSING_BASE);
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` " +
                "PROPERTIES (\"type\" = \"incremental\", \"base_snapshot\" = \"   \")", MISSING_BASE);
    }

    @Test
    public void baseSnapshotWithoutIncrementalTypeIsRejected() {
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` PROPERTIES (\"base_snapshot\" = \"s1\")",
                BASE_WITHOUT_INCREMENTAL);
        analyzeFail("BACKUP DATABASE test SNAPSHOT s2 TO `repo` " +
                "PROPERTIES (\"type\" = \"full\", \"base_snapshot\" = \"s1\")", BASE_WITHOUT_INCREMENTAL);
    }

    @Test
    public void incrementalExternalCatalogBackupIsRejected() {
        analyzeFail("BACKUP EXTERNAL CATALOG (catalog1) SNAPSHOT s2 TO `repo` " +
                "PROPERTIES (\"type\" = \"incremental\", \"base_snapshot\" = \"s1\")", EXTERNAL_CATALOG);
        analyzeFail("BACKUP ALL EXTERNAL CATALOGS SNAPSHOT s2 TO `repo` " +
                "PROPERTIES (\"type\" = \"incremental\", \"base_snapshot\" = \"s1\")", EXTERNAL_CATALOG);
    }
}
