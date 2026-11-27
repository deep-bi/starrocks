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

package bi.deep.starrocks.extension.warehouse;

import com.starrocks.common.DdlException;
import com.starrocks.sql.ast.warehouse.AlterWarehouseStmt;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.sql.ast.warehouse.DropWarehouseStmt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The leader's periodic check of StarMgr against the stored warehouses, with the manager and an in-memory
 * StarMgr.
 */
public class StarMgrSyncTest {
    private FakeFeEnvironment fe;
    private MultiWarehouseManager manager;

    @BeforeEach
    public void before() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false));
        fe = new FakeFeEnvironment();
        manager = serve(fe);
    }

    @AfterEach
    public void after() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    /** A manager that the FE serves from. */
    private static MultiWarehouseManager serve(FakeFeEnvironment fe) {
        MultiWarehouseManager manager = new MultiWarehouseManager(fe);
        manager.initDefaultWarehouse();
        fe.servingManager = manager;
        return manager;
    }

    private long create(String name, int replicaNumber) throws DdlException {
        Map<String, String> properties = new HashMap<>();
        properties.put("replica_number", String.valueOf(replicaNumber));
        manager.createWarehouse(new CreateWarehouseStmt(false, name, properties, null));
        return ((UserDefinedWarehouse) manager.getWarehouse(name)).getWorkerGroupId();
    }

    private static AlterWarehouseStmt alter(String name, int replicaNumber) {
        return new AlterWarehouseStmt(name, Collections.singletonMap("replica_number", String.valueOf(replicaNumber)));
    }

    // ------------------------------------------------------------------ the case it exists for

    /**
     * ALTER puts the new number into StarMgr, then the FE dies before the edit log has it, and before it could
     * take the number back. The next leader starts from the edit log, which still says 1, and finds 3 in StarMgr.
     */
    @Test
    public void theNextLeaderUndoesAnAlterThatNeverCommitted() throws Exception {
        long workerGroupId = create("etl", 1);
        fe.failJournal = new IllegalStateException("the FE dies here");
        fe.onlyAllowedReplicaNumber = 3;
        Assertions.assertThrows(IllegalStateException.class, () -> manager.alterWarehouse(alter("etl", 3)));
        Assertions.assertEquals(3, fe.workerGroups.get(workerGroupId), "StarMgr kept the number of the lost statement");
        fe.failJournal = null;
        fe.onlyAllowedReplicaNumber = null;

        MultiWarehouseManager nextLeader = new MultiWarehouseManager(fe);
        nextLeader.initDefaultWarehouse();
        fe.replayInto(nextLeader);
        fe.servingManager = nextLeader;
        int journalEntries = fe.journal.size();
        Assertions.assertEquals(1, ((UserDefinedWarehouse) nextLeader.getWarehouse("etl")).getReplicaNumber());

        Assertions.assertEquals(1, StarMgrSync.run(fe));

        Assertions.assertEquals(1, fe.workerGroups.get(workerGroupId));
        Assertions.assertEquals(journalEntries, fe.journal.size(), "the check writes to StarMgr, never to the edit log");
        Assertions.assertEquals(0, StarMgrSync.run(fe), "nothing left to correct in the next round");
    }

    /**
     * A follower can be a moment behind the leader. It must not touch StarMgr, or it would undo a statement the
     * leader is about to commit.
     */
    @Test
    public void anFeThatIsNotTheLeaderNeverTouchesStarMgr() throws Exception {
        long workerGroupId = create("etl", 1);
        fe.workerGroups.put(workerGroupId, 3);
        int starMgrCalls = fe.starMgrCalls;

        fe.activeLeader = false;

        Assertions.assertEquals(0, StarMgrSync.run(fe));
        Assertions.assertEquals(3, fe.workerGroups.get(workerGroupId));
        Assertions.assertEquals(starMgrCalls, fe.starMgrCalls);
    }

    @Test
    public void anAlterThatCommittedIsLeftAlone() throws Exception {
        long workerGroupId = create("etl", 1);
        manager.alterWarehouse(alter("etl", 3));
        int starMgrCalls = fe.starMgrCalls;

        Assertions.assertEquals(0, StarMgrSync.run(fe));

        Assertions.assertEquals(3, fe.workerGroups.get(workerGroupId));
        Assertions.assertEquals(starMgrCalls + 1, fe.starMgrCalls, "one question to StarMgr, no change");
    }

    // ------------------------------------------------------------------ rounds that meet trouble

    @Test
    public void aSlowPeriodicStarMgrReadDoesNotBlockCreatingAnotherWarehouse() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        fe = new FakeFeEnvironment() {
            @Override
            public int replicaNumberInStarMgr(long workerGroupId) throws DdlException {
                int replicas = super.replicaNumberInStarMgr(workerGroupId);
                readStarted.countDown();
                try {
                    Assertions.assertTrue(releaseRead.await(30, TimeUnit.SECONDS), "StarMgr read was never released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("StarMgr read was interrupted", e);
                }
                return replicas;
            }
        };
        manager = serve(fe);
        create("etl", 1);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> round = workers.submit(() -> StarMgrSync.run(fe));
            Assertions.assertTrue(readStarted.await(5, TimeUnit.SECONDS), "periodic check did not reach StarMgr");

            Future<Long> createReports = workers.submit(() -> create("reports", 1));
            long reportsGroup = Assertions.assertDoesNotThrow(() -> createReports.get(5, TimeUnit.SECONDS),
                    "CREATE WAREHOUSE reports must finish while the StarMgr read for etl is pending");
            Assertions.assertFalse(round.isDone(), "the periodic check must still be waiting for the read");

            releaseRead.countDown();
            Assertions.assertEquals(0, round.get(5, TimeUnit.SECONDS));
            Assertions.assertEquals(reportsGroup, ((UserDefinedWarehouse) manager.getWarehouse("reports")).getWorkerGroupId());
        } finally {
            releaseRead.countDown();
            workers.shutdownNow();
            Assertions.assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "warehouse test workers did not stop");
        }
    }

    @Test
    public void aPeriodicReadCannotUndoAnAlterCommittedDuringTheRead() throws Exception {
        fe = new FakeFeEnvironment() {
            @Override
            public int replicaNumberInStarMgr(long workerGroupId) throws DdlException {
                int observed = super.replicaNumberInStarMgr(workerGroupId);
                servingManager.alterWarehouse(alter("etl", 3));
                return observed;
            }
        };
        manager = serve(fe);
        long group = create("etl", 2);
        fe.workerGroups.put(group, 5);
        int calls = fe.starMgrCalls;

        Assertions.assertEquals(0, StarMgrSync.run(fe));

        Assertions.assertEquals(3, ((UserDefinedWarehouse) manager.getWarehouse("etl")).getReplicaNumber());
        Assertions.assertEquals(3, fe.workerGroups.get(group));
        Assertions.assertEquals(calls + 2, fe.starMgrCalls, "only the periodic read and the ALTER write");
    }

    @Test
    public void aPeriodicReadCannotCorrectAGroupDroppedDuringTheRead() throws Exception {
        fe = new FakeFeEnvironment() {
            @Override
            public int replicaNumberInStarMgr(long workerGroupId) throws DdlException {
                int observed = super.replicaNumberInStarMgr(workerGroupId);
                servingManager.dropWarehouse(new DropWarehouseStmt(false, "etl"));
                create("etl", 3);
                return observed;
            }
        };
        manager = serve(fe);
        long oldGroup = create("etl", 2);
        fe.workerGroups.put(oldGroup, 5);
        int calls = fe.starMgrCalls;

        Assertions.assertEquals(0, StarMgrSync.run(fe));

        long newGroup = ((UserDefinedWarehouse) manager.getWarehouse("etl")).getWorkerGroupId();
        Assertions.assertNotEquals(oldGroup, newGroup);
        Assertions.assertEquals(Collections.singletonMap(newGroup, 3), fe.workerGroups);
        Assertions.assertEquals(calls + 3, fe.starMgrCalls, "only the periodic read, DROP and CREATE");
    }

    /**
     * A worker group StarMgr does not know cannot be corrected. The round reports it and still corrects the
     * others.
     */
    @Test
    public void oneWorkerGroupThatCannotBeReadDoesNotStopTheOthers() throws Exception {
        long gone = create("adhoc", 1);
        long drifted = create("etl", 2);
        fe.workerGroups.remove(gone);
        fe.workerGroups.put(drifted, 4);

        Assertions.assertEquals(1, StarMgrSync.run(fe));

        Assertions.assertEquals(Collections.singletonMap(drifted, 2), fe.workerGroups);
    }

    /**
     * With StarMgr down the round corrects nothing, raises nothing, and the next round does the work.
     */
    @Test
    public void aRoundThatFailsIsRepeatedByTheNextOne() throws Exception {
        long workerGroupId = create("etl", 2);
        fe.workerGroups.put(workerGroupId, 4);

        fe.failReadWorkerGroup = new IllegalStateException("StarMgr is not reachable");
        Assertions.assertEquals(0, StarMgrSync.run(fe));
        fe.failReadWorkerGroup = null;
        fe.failUpdateWorkerGroup = new DdlException("Failed to update worker group. error: timeout");
        Assertions.assertEquals(0, StarMgrSync.run(fe));
        Assertions.assertEquals(4, fe.workerGroups.get(workerGroupId));

        fe.failUpdateWorkerGroup = null;
        Assertions.assertEquals(1, StarMgrSync.run(fe));
        Assertions.assertEquals(2, fe.workerGroups.get(workerGroupId));
    }

    /**
     * The round lists the warehouses first and then takes them one by one. One that is dropped in between is
     * skipped: its worker group is gone, and asking StarMgr for it would only report an error.
     */
    @Test
    public void aWarehouseDroppedDuringTheRoundIsSkipped() throws Exception {
        // The first question to StarMgr drops the second warehouse, as a DROP that ran in between would.
        FakeFeEnvironment droppingFe = new FakeFeEnvironment() {
            private boolean dropped;

            @Override
            public int replicaNumberInStarMgr(long workerGroupId) throws DdlException {
                if (!dropped) {
                    dropped = true;
                    servingManager.dropWarehouse(new DropWarehouseStmt(false, "etl"));
                }
                return super.replicaNumberInStarMgr(workerGroupId);
            }
        };
        fe = droppingFe;
        manager = serve(droppingFe);
        create("adhoc", 1);
        long etlGroup = create("etl", 2);
        int starMgrCalls = droppingFe.starMgrCalls;

        Assertions.assertEquals(0, StarMgrSync.run(droppingFe));

        Assertions.assertFalse(manager.warehouseExists("etl"));
        Assertions.assertEquals(Collections.singletonList(etlGroup), droppingFe.deletedWorkerGroups);
        Assertions.assertEquals(starMgrCalls + 2, droppingFe.starMgrCalls,
                "one question for adhoc, one delete for the DROP, and no question for the dropped warehouse");
    }

    // ------------------------------------------------------------------ installation

    @Test
    public void installStartsOneCheckPerFe() throws Exception {
        FakeFeEnvironment other = new FakeFeEnvironment();

        Assertions.assertTrue(StarMgrSync.install(other));
        Assertions.assertFalse(StarMgrSync.install(other), "the second call must not start another one");
        Assertions.assertEquals(Collections.singleton(StarMgrSync.NAME), other.periodicTasks.keySet());
        Assertions.assertEquals(StarMgrSync.CHECK_INTERVAL_MS, other.periodicIntervals.get(StarMgrSync.NAME));

        // The manager starts the check itself, and the task works on whatever manager the FE serves from.
        long workerGroupId = create("etl", 2);
        fe.workerGroups.put(workerGroupId, 4);
        fe.periodicTasks.get(StarMgrSync.NAME).run();
        Assertions.assertEquals(2, fe.workerGroups.get(workerGroupId));
    }

    /**
     * An FE whose warehouse manager is not this extension's has nothing to check.
     */
    @Test
    public void withoutAManagerOfThisExtensionTheCheckDoesNothing() {
        fe.servingManager = null;

        Assertions.assertEquals(0, StarMgrSync.run(fe));
        Assertions.assertEquals(0, fe.starMgrCalls);
    }
}
