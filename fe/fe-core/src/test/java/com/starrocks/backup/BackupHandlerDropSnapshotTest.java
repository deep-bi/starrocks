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

import com.starrocks.common.DdlException;
import com.starrocks.sql.ast.DropSnapshotStmt;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Covers BackupHandler.dropSnapshot with a real handler. RepositoryMgr and BackupHandler are Daemon threads,
 * which JMockit cannot mock class-wide, so the repository lookup and the storage calls are faked with MockUps.
 */
public class BackupHandlerDropSnapshotTest {
    private static final String REPO = "test_repo";

    private final Map<String, Repository> repos = new HashMap<>();
    private final Set<String> failingSnapshots = new HashSet<>();
    private final List<String> deletedSnapshots = new ArrayList<>();
    private final List<String> timestampCalls = new ArrayList<>();
    private Status timestampStatus = Status.OK;

    private BackupHandler handler;

    @BeforeEach
    public void setUp() {
        repos.put(REPO, new Repository(1, REPO, false, "s3://bucket/backup", null));
        repos.put("readonly_repo", new Repository(2, "readonly_repo", true, "s3://bucket/readonly", null));

        new MockUp<RepositoryMgr>() {
            @Mock
            public Repository getRepo(String repoName) {
                return repos.get(repoName);
            }
        };
        new MockUp<Repository>() {
            @Mock
            public Status deleteSnapshot(String snapshotName) {
                if (failingSnapshots.contains(snapshotName)) {
                    return new Status(Status.ErrCode.COMMON_ERROR, "cannot delete " + snapshotName);
                }
                deletedSnapshots.add(snapshotName);
                return Status.OK;
            }

            @Mock
            public Status deleteSnapshotsByTimestamp(String operator, String timestamp) {
                timestampCalls.add(operator + " " + timestamp);
                return timestampStatus;
            }
        };

        handler = new BackupHandler();
    }

    private static DropSnapshotStmt stmt(String repoName) {
        return new DropSnapshotStmt(repoName, null);
    }

    private boolean isSeqLockHeld() throws Exception {
        Field field = BackupHandler.class.getDeclaredField("seqlock");
        field.setAccessible(true);
        return ((ReentrantLock) field.get(handler)).isLocked();
    }

    @Test
    public void testDropSnapshotRepositoryNotFound() {
        DropSnapshotStmt stmt = stmt("nonexistent_repo");
        stmt.setSnapshotName("snapshot1");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("Repository nonexistent_repo does not exist"), e.getMessage());
        Assertions.assertTrue(deletedSnapshots.isEmpty());
    }

    @Test
    public void testDropSnapshotReadOnlyRepository() {
        DropSnapshotStmt stmt = stmt("readonly_repo");
        stmt.setSnapshotName("snapshot1");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("is read only"), e.getMessage());
        Assertions.assertTrue(deletedSnapshots.isEmpty());
    }

    @Test
    public void testDropSnapshotSingleSnapshotSuccess() throws Exception {
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.setSnapshotName("snapshot1");

        handler.dropSnapshot(stmt);

        Assertions.assertEquals(List.of("snapshot1"), deletedSnapshots);
        Assertions.assertFalse(isSeqLockHeld());
    }

    @Test
    public void testDropSnapshotSingleSnapshotFailure() throws Exception {
        failingSnapshots.add("snapshot1");
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.setSnapshotName("snapshot1");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("Failed to drop snapshot 'snapshot1'"), e.getMessage());
        // the sequence lock must be released on the failure path as well
        Assertions.assertFalse(isSeqLockHeld());
    }

    @Test
    public void testDropSnapshotMultipleSnapshotsAllSuccess() throws Exception {
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.addSnapshotName("snapshot1");
        stmt.addSnapshotName("snapshot2");
        stmt.addSnapshotName("snapshot3");

        handler.dropSnapshot(stmt);

        Assertions.assertEquals(List.of("snapshot1", "snapshot2", "snapshot3"), deletedSnapshots);
    }

    @Test
    public void testDropSnapshotMultipleSnapshotsPartialSuccess() {
        failingSnapshots.add("snapshot2");
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.addSnapshotName("snapshot1");
        stmt.addSnapshotName("snapshot2");
        stmt.addSnapshotName("snapshot3");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("Failed to drop 1 out of 3 snapshots"), e.getMessage());
        // a failing snapshot does not stop the others from being dropped
        Assertions.assertEquals(List.of("snapshot1", "snapshot3"), deletedSnapshots);
    }

    @Test
    public void testDropSnapshotMultipleSnapshotsAllFail() {
        failingSnapshots.add("snapshot1");
        failingSnapshots.add("snapshot2");
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.addSnapshotName("snapshot1");
        stmt.addSnapshotName("snapshot2");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("Failed to drop 2 out of 2 snapshots"), e.getMessage());
        Assertions.assertTrue(deletedSnapshots.isEmpty());
    }

    @Test
    public void testDropSnapshotByTimestampSuccess() throws Exception {
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.setTimestampOperator("<=");
        stmt.setTimestamp("2024-01-01-12-00-00");

        handler.dropSnapshot(stmt);

        Assertions.assertEquals(List.of("<= 2024-01-01-12-00-00"), timestampCalls);
        Assertions.assertTrue(deletedSnapshots.isEmpty());
    }

    @Test
    public void testDropSnapshotByTimestampFailure() throws Exception {
        timestampStatus = new Status(Status.ErrCode.COMMON_ERROR, "listing failed");
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.setTimestampOperator(">=");
        stmt.setTimestamp("2024-01-01-12-00-00");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("Failed to drop snapshots with timestamp filter"), e.getMessage());
        Assertions.assertFalse(isSeqLockHeld());
    }

    @Test
    public void testDropSnapshotTimestampWithoutOperator() {
        // a timestamp without an operator is not a valid filter
        DropSnapshotStmt stmt = stmt(REPO);
        stmt.setTimestamp("2024-01-01-12-00-00");

        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt));
        Assertions.assertTrue(e.getMessage().contains("Must specify snapshot name or timestamp filter"), e.getMessage());
        Assertions.assertTrue(timestampCalls.isEmpty());
    }

    @Test
    public void testDropSnapshotNoValidCriteria() throws Exception {
        DdlException e = Assertions.assertThrows(DdlException.class, () -> handler.dropSnapshot(stmt(REPO)));
        Assertions.assertTrue(e.getMessage().contains("Must specify snapshot name or timestamp filter"), e.getMessage());
        Assertions.assertFalse(isSeqLockHeld());
    }
}
