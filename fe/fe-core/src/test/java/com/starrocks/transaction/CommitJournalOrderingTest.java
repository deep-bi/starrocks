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

package com.starrocks.transaction;

import com.google.common.collect.Lists;
import com.starrocks.catalog.FakeEditLog;
import com.starrocks.catalog.FakeGlobalStateMgr;
import com.starrocks.catalog.GlobalStateMgrTestUtil;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.common.Config;
import com.starrocks.journal.LeaderTransferException;
import com.starrocks.metric.MetricRepo;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.task.PublishVersionTask;
import com.starrocks.thrift.TPartitionVersionInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.starrocks.catalog.GlobalStateMgrTestUtil.testBackendId1;
import static com.starrocks.catalog.GlobalStateMgrTestUtil.testBackendId2;
import static com.starrocks.catalog.GlobalStateMgrTestUtil.testBackendId3;
import static com.starrocks.catalog.GlobalStateMgrTestUtil.testDbId1;
import static com.starrocks.catalog.GlobalStateMgrTestUtil.testPartitionId1;
import static com.starrocks.catalog.GlobalStateMgrTestUtil.testTableId1;
import static com.starrocks.catalog.GlobalStateMgrTestUtil.testTabletId1;

/**
 * Covers the window in {@link DatabaseTransactionMgr#commitPreparedTransaction} where a transaction is already
 * COMMITTED in memory but its COMMITTED journal entry has not been written yet. Publishing in that window lets
 * BEs apply a version that a restarted FE never learns about, so the publish daemon must not see the
 * transaction until the entry is durable.
 */
public class CommitJournalOrderingTest {

    private static final long WAIT_SECONDS = 10;

    private final TransactionState.TxnCoordinator coordinator =
            new TransactionState.TxnCoordinator(TransactionState.TxnSourceType.BE, "be1");

    private boolean originEnableMetricCalculator;
    private boolean originEnableNewPublish;
    private GlobalStateMgr leader;
    private BlockingJournal journal;
    private GlobalTransactionMgr leaderTxnMgr;
    private DatabaseTransactionMgr leaderDbTxnMgr;
    private Thread committer;
    private final AtomicReference<Throwable> commitError = new AtomicReference<>();

    /**
     * Edit log that keeps a serialized copy of every successfully written transaction state, so later in-memory
     * mutations of the same TransactionState object cannot leak into the "persisted" journal. It can block the
     * COMMITTED write of one transaction and then either complete it or fail it.
     */
    private static class BlockingJournal extends EditLog {
        private final List<String> persistedTxnStates = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch commitPersistEntered = new CountDownLatch(1);
        private final CountDownLatch releaseCommitPersist = new CountDownLatch(1);
        private volatile long blockedTxnId = -1;
        private volatile RuntimeException failure;

        BlockingJournal() {
            super(new ArrayBlockingQueue<>(1));
        }

        void blockCommittedWrite(long txnId, RuntimeException failure) {
            this.blockedTxnId = txnId;
            this.failure = failure;
        }

        @Override
        public void logInsertTransactionState(TransactionState transactionState) {
            // the real journal serializes at submit time, before waiting for the write to be acknowledged
            String snapshot = GsonUtils.GSON.toJson(transactionState);
            if (transactionState.getTransactionId() == blockedTxnId
                    && transactionState.getTransactionStatus() == TransactionStatus.COMMITTED) {
                commitPersistEntered.countDown();
                try {
                    if (!releaseCommitPersist.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("commit persistence was never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                if (failure != null) {
                    throw failure;
                }
            }
            persistedTxnStates.add(snapshot);
        }

        boolean hasPersisted(long txnId, TransactionStatus status) {
            synchronized (persistedTxnStates) {
                return persistedTxnStates.stream()
                        .map(json -> GsonUtils.GSON.fromJson(json, TransactionState.class))
                        .anyMatch(s -> s.getTransactionId() == txnId && s.getTransactionStatus() == status);
            }
        }

        List<TransactionState> replayableEntries() {
            synchronized (persistedTxnStates) {
                List<TransactionState> entries = new ArrayList<>();
                for (String json : persistedTxnStates) {
                    entries.add(GsonUtils.GSON.fromJson(json, TransactionState.class));
                }
                return entries;
            }
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        Config.label_keep_max_second = 10;
        new FakeEditLog();
        new FakeGlobalStateMgr();
        new FakeTransactionIDGenerator();
        // MetricRepo.init reads GlobalStateMgr.getCurrentState(), so the state must exist first
        leader = GlobalStateMgrTestUtil.createTestState();
        originEnableMetricCalculator = Config.enable_metric_calculator;
        originEnableNewPublish = Config.enable_new_publish_mechanism;
        Config.enable_metric_calculator = false;
        MetricRepo.init();

        journal = new BlockingJournal();
        leader.setEditLog(journal);
        leaderTxnMgr = leader.getGlobalTransactionMgr();
        // DatabaseTransactionMgr captures the edit log in its constructor, so rebuild it after the swap
        leaderTxnMgr.removeDatabaseTransactionMgr(testDbId1);
        leaderTxnMgr.addDatabaseTransactionMgr(testDbId1);
        leaderDbTxnMgr = leaderTxnMgr.getDatabaseTransactionMgr(testDbId1);
    }

    @AfterEach
    public void tearDown() {
        Config.enable_metric_calculator = originEnableMetricCalculator;
        Config.enable_new_publish_mechanism = originEnableNewPublish;
    }

    private static List<TabletCommitInfo> allReplicasCommitted() {
        return Lists.newArrayList(
                new TabletCommitInfo(testTabletId1, testBackendId1),
                new TabletCommitInfo(testTabletId1, testBackendId2),
                new TabletCommitInfo(testTabletId1, testBackendId3));
    }

    private static PhysicalPartition partitionOf(GlobalStateMgr state) {
        return state.getLocalMetastore().getTable(testDbId1, testTableId1)
                .getPartition(testPartitionId1).getDefaultPhysicalPartition();
    }

    private static boolean containsTxn(List<TransactionState> states, long txnId) {
        return states.stream().anyMatch(s -> s.getTransactionId() == txnId);
    }

    /**
     * Begins a transaction, starts committing it on another thread and returns once the commit is parked on
     * its COMMITTED journal write. {@code failure} is thrown from that write when it is released, or null for
     * a successful write.
     */
    private long startCommitBlockedOnJournal(RuntimeException failure) throws Exception {
        long txnId = leaderTxnMgr.beginTransaction(testDbId1, Lists.newArrayList(testTableId1),
                "commit_journal_ordering", coordinator,
                TransactionState.LoadJobSourceType.BACKEND_STREAMING, Config.stream_load_default_timeout_second);
        journal.blockCommittedWrite(txnId, failure);

        committer = new Thread(() -> {
            try {
                leaderTxnMgr.commitTransaction(testDbId1, txnId, allReplicasCommitted(), Lists.newArrayList(), null);
            } catch (Throwable t) {
                commitError.set(t);
            }
        }, "committer");
        committer.start();

        Assertions.assertTrue(journal.commitPersistEntered.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "commit never reached COMMITTED journal persistence");
        Assertions.assertFalse(journal.hasPersisted(txnId, TransactionStatus.COMMITTED));
        return txnId;
    }

    private void releaseJournalWrite() throws InterruptedException {
        journal.releaseCommitPersist.countDown();
        committer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        Assertions.assertFalse(committer.isAlive());
    }

    /**
     * Runs the task-creation half of PublishVersionDaemon.publishVersionForOlapTable and returns the highest
     * version handed to any BE, or -1 if no task was created.
     */
    private static long publishReadyTransactions(GlobalTransactionMgr txnMgr) {
        long beVersion = -1;
        for (TransactionState state : txnMgr.getReadyToPublishTransactions(Config.enable_new_publish_mechanism)) {
            List<PublishVersionTask> tasks = state.createPublishVersionTask();
            if (!tasks.isEmpty()) {
                state.setHasSendTask(true);
            }
            for (PublishVersionTask task : tasks) {
                for (TPartitionVersionInfo info : task.toThrift().getPartition_version_infos()) {
                    beVersion = Math.max(beVersion, info.getVersion());
                }
                task.setFinished(true);
            }
        }
        return beVersion;
    }

    private GlobalTransactionMgr restartFromJournal() throws Exception {
        List<TransactionState> journalEntries = journal.replayableEntries();
        GlobalStateMgr newLeader = GlobalStateMgrTestUtil.createTestState();
        GlobalTransactionMgr newTxnMgr = newLeader.getGlobalTransactionMgr();
        for (TransactionState entry : journalEntries) {
            newTxnMgr.replayUpsertTransactionState(entry);
        }
        return newTxnMgr;
    }

    @ParameterizedTest(name = "enable_new_publish_mechanism={0}")
    @ValueSource(booleans = {false, true})
    public void testCommittedTransactionIsNotPublishedBeforeJournalPersistence(boolean newPublish)
            throws Exception {
        Config.enable_new_publish_mechanism = newPublish;
        PhysicalPartition leaderPartition = partitionOf(leader);
        long visibleBefore = leaderPartition.getVisibleVersion();
        long nextBefore = leaderPartition.getNextVersion();
        Assertions.assertEquals(visibleBefore + 1, nextBefore);

        // simulate the journal writer failing because leadership moved while the write was in flight
        long txnId = startCommitBlockedOnJournal(new LeaderTransferException());

        // ---- persistence of COMMITTED is blocked from here on ----
        // the in-flight commit still counts as committed for callers such as alter jobs that wait on it
        Assertions.assertTrue(containsTxn(leaderDbTxnMgr.getCommittedTxnList(), txnId));
        Assertions.assertTrue(leaderTxnMgr.existCommittedTxns(testDbId1, testTableId1, null));
        Assertions.assertTrue(leaderDbTxnMgr.getTransactionState(txnId).isCommitJournalPending());

        // but the publish daemon must not see it until the journal entry is durable
        Assertions.assertFalse(containsTxn(leaderTxnMgr.getReadyToPublishTransactions(newPublish), txnId));
        Assertions.assertEquals(-1, publishReadyTransactions(leaderTxnMgr),
                "no publish task may be created before COMMITTED is persisted");

        // ---- let the COMMITTED write fail ----
        releaseJournalWrite();
        Assertions.assertInstanceOf(LeaderTransferException.class, commitError.get());
        Assertions.assertFalse(journal.hasPersisted(txnId, TransactionStatus.COMMITTED));

        // the old leader keeps the transaction COMMITTED in memory, but never publishes it
        TransactionState oldLeaderState = leaderDbTxnMgr.getTransactionState(txnId);
        Assertions.assertEquals(TransactionStatus.COMMITTED, oldLeaderState.getTransactionStatus());
        Assertions.assertTrue(oldLeaderState.isCommitJournalPending());
        Assertions.assertEquals(-1, publishReadyTransactions(leaderTxnMgr));

        // ---- new leader / restarted FE: rebuild state only from what reached the journal ----
        GlobalTransactionMgr newTxnMgr = restartFromJournal();
        DatabaseTransactionMgr newDbTxnMgr = newTxnMgr.getDatabaseTransactionMgr(testDbId1);
        Assertions.assertNull(newDbTxnMgr.getTransactionState(txnId));

        // no BE ever received the lost version, so FE and BE agree on the visible version
        PhysicalPartition newPartition = partitionOf(GlobalStateMgr.getCurrentState());
        Assertions.assertEquals(visibleBefore, newPartition.getVisibleVersion());
        Assertions.assertEquals(nextBefore, newPartition.getNextVersion());

        // the next load reuses the lost version, which is safe because no BE holds data for it
        long nextTxnId = newTxnMgr.beginTransaction(testDbId1, Lists.newArrayList(testTableId1),
                "commit_journal_ordering_next", coordinator,
                TransactionState.LoadJobSourceType.BACKEND_STREAMING, Config.stream_load_default_timeout_second);
        newTxnMgr.commitTransaction(testDbId1, nextTxnId, allReplicasCommitted(), Lists.newArrayList(), null);
        Assertions.assertEquals(nextBefore, publishReadyTransactions(newTxnMgr));
    }

    @ParameterizedTest(name = "enable_new_publish_mechanism={0}")
    @ValueSource(booleans = {false, true})
    public void testCommittedTransactionIsPublishedAfterJournalPersistence(boolean newPublish) throws Exception {
        Config.enable_new_publish_mechanism = newPublish;
        long nextBefore = partitionOf(leader).getNextVersion();

        long txnId = startCommitBlockedOnJournal(null);
        Assertions.assertFalse(containsTxn(leaderTxnMgr.getReadyToPublishTransactions(newPublish), txnId));

        releaseJournalWrite();
        Assertions.assertNull(commitError.get());
        Assertions.assertTrue(journal.hasPersisted(txnId, TransactionStatus.COMMITTED));

        TransactionState state = leaderDbTxnMgr.getTransactionState(txnId);
        Assertions.assertFalse(state.isCommitJournalPending());
        Assertions.assertTrue(containsTxn(leaderTxnMgr.getReadyToPublishTransactions(newPublish), txnId));
        Assertions.assertEquals(nextBefore, publishReadyTransactions(leaderTxnMgr));

        // a restarted FE replays the durable COMMITTED entry and retries the publish
        GlobalTransactionMgr newTxnMgr = restartFromJournal();
        TransactionState replayed = newTxnMgr.getDatabaseTransactionMgr(testDbId1).getTransactionState(txnId);
        Assertions.assertEquals(TransactionStatus.COMMITTED, replayed.getTransactionStatus());
        Assertions.assertFalse(replayed.isCommitJournalPending());
        Assertions.assertEquals(nextBefore, publishReadyTransactions(newTxnMgr));
    }
}
