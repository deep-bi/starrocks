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

import com.google.common.collect.Maps;
import com.starrocks.backup.Status.ErrCode;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Covers Repository.deleteSnapshot against a fake object store. On S3 the recursive delete can leave
 * objects and the 0-byte directory marker behind, so the snapshot must be cleaned up explicitly and the
 * path verified as gone, otherwise SHOW SNAPSHOT keeps listing it.
 */
public class RepositoryDeleteSnapshotTest {
    private static final String SNAPSHOT_PATH = "s3://bucket/backup/__starrocks_repository_repo1/__ss_snap1";

    private final List<String> deletedPaths = new ArrayList<>();
    private final List<String> listedPaths = new ArrayList<>();
    private final Map<String, Status> deleteResults = new HashMap<>();
    private final List<RemoteFile> remainingFiles = new ArrayList<>();
    private Status listResult = Status.OK;
    private Status checkPathExistResult = new Status(ErrCode.NOT_FOUND, "remote path does not exist");

    private Repository repo;

    @BeforeEach
    public void setUp() {
        new MockUp<BlobStorage>() {
            @Mock
            public Status delete(String remotePath) {
                deletedPaths.add(remotePath);
                return deleteResults.getOrDefault(remotePath, Status.OK);
            }

            @Mock
            public Status list(String remotePath, List<RemoteFile> result) {
                listedPaths.add(remotePath);
                result.addAll(remainingFiles);
                return listResult;
            }

            @Mock
            public Status checkPathExist(String remotePath) {
                Assertions.assertEquals(SNAPSHOT_PATH, remotePath);
                return checkPathExistResult;
            }
        };
        repo = new Repository(1, "repo1", false, "s3://bucket/backup", new BlobStorage("broker", Maps.newHashMap()));
    }

    @Test
    public void testDeleteSnapshotRemovesLeftoverObjectsAndMarker() {
        remainingFiles.add(new RemoteFile("__info_snap1", true, 10));
        remainingFiles.add(new RemoteFile("__meta_snap1", true, 20));

        Status status = repo.deleteSnapshot("snap1");

        Assertions.assertTrue(status.ok(), status.getErrMsg());
        Assertions.assertEquals(List.of(SNAPSHOT_PATH + "/*"), listedPaths);
        Assertions.assertEquals(List.of(
                SNAPSHOT_PATH,
                SNAPSHOT_PATH + "/__info_snap1",
                SNAPSHOT_PATH + "/__meta_snap1",
                SNAPSHOT_PATH + "/"), deletedPaths);
    }

    @Test
    public void testDeleteSnapshotRemovesDirectoryMarkerWhenNothingIsLeft() {
        Status status = repo.deleteSnapshot("snap1");

        Assertions.assertTrue(status.ok(), status.getErrMsg());
        Assertions.assertEquals(List.of(SNAPSHOT_PATH, SNAPSHOT_PATH + "/"), deletedPaths);
    }

    @Test
    public void testDeleteSnapshotFailsWhenDirectoryStillExists() {
        checkPathExistResult = Status.OK;

        Status status = repo.deleteSnapshot("snap1");

        Assertions.assertFalse(status.ok());
        Assertions.assertTrue(status.getErrMsg().contains("directory still exists after deletion"), status.getErrMsg());
    }

    @Test
    public void testDeleteSnapshotFailsWhenDeletionCannotBeVerified() {
        checkPathExistResult = new Status(ErrCode.COMMON_ERROR, "broker unavailable");

        Status status = repo.deleteSnapshot("snap1");

        Assertions.assertFalse(status.ok());
        Assertions.assertTrue(status.getErrMsg().contains("Failed to verify snapshot deletion 'snap1'"),
                status.getErrMsg());
        Assertions.assertTrue(status.getErrMsg().contains("broker unavailable"), status.getErrMsg());
    }

    @Test
    public void testDeleteSnapshotSucceedsWhenRecursiveDeleteErrorsButPathIsGone() {
        // the recursive delete can report an error after removing the contents; the outcome is decided by
        // whether the snapshot path is gone at the end
        deleteResults.put(SNAPSHOT_PATH, new Status(ErrCode.COMMON_ERROR, "partial delete"));
        listResult = new Status(ErrCode.COMMON_ERROR, "list failed");

        Status status = repo.deleteSnapshot("snap1");

        Assertions.assertTrue(status.ok(), status.getErrMsg());
        Assertions.assertEquals(List.of(SNAPSHOT_PATH, SNAPSHOT_PATH + "/"), deletedPaths);
    }

    @Test
    public void testDeleteSnapshotRejectsEmptyName() {
        Status status = repo.deleteSnapshot("");

        Assertions.assertFalse(status.ok());
        Assertions.assertTrue(status.getErrMsg().contains("Snapshot name cannot be empty"), status.getErrMsg());
        Assertions.assertTrue(deletedPaths.isEmpty());
    }
}
