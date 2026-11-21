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

import com.google.gson.stream.JsonReader;
import com.starrocks.common.DdlException;
import com.starrocks.persist.ImageWriter;
import com.starrocks.persist.OperationType;
import com.starrocks.persist.metablock.SRMetaBlockReader;
import com.starrocks.persist.metablock.SRMetaBlockReaderV2;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.warehouse.AlterWarehouseStmt;
import com.starrocks.sql.ast.warehouse.CreateWarehouseStmt;
import com.starrocks.sql.ast.warehouse.DropWarehouseStmt;
import com.starrocks.sql.ast.warehouse.ResumeWarehouseStmt;
import com.starrocks.sql.ast.warehouse.SuspendWarehouseStmt;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.DefaultWarehouse;
import com.starrocks.warehouse.Warehouse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Exercises the manager against an in-memory FE. No FE class is mocked: the statements, the edit-log entries
 * and the image go through the real FE classes.
 */
public class UserWarehouseManagerTest {
    private FakeFeEnvironment fe;
    private UserWarehouseManager manager;

    @BeforeEach
    public void before() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.of(true, false));
        fe = new FakeFeEnvironment();
        manager = new UserWarehouseManager(fe);
        manager.initDefaultWarehouse();
    }

    @AfterEach
    public void after() {
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
    }

    // ------------------------------------------------------------------ helpers

    private static CreateWarehouseStmt create(String name) {
        return new CreateWarehouseStmt(false, name, null, null);
    }

    private static CreateWarehouseStmt create(String name, boolean ifNotExists, String comment, String... keyValues) {
        return new CreateWarehouseStmt(ifNotExists, name, properties(keyValues), comment);
    }

    private static AlterWarehouseStmt alter(String name, String... keyValues) {
        return new AlterWarehouseStmt(name, properties(keyValues));
    }

    private static Map<String, String> properties(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private UserWarehouse user(String name) {
        return (UserWarehouse) manager.getWarehouse(name);
    }

    private static String messageOf(Class<? extends Throwable> type, org.junit.jupiter.api.function.Executable code) {
        return Assertions.assertThrows(type, code).getMessage();
    }

    // ------------------------------------------------------------------ start

    /**
     * The FE calls initDefaultWarehouse on every start, and again on image load and in checkpoint copies. The
     * default warehouse must appear, and the watch for lost metadata must start exactly once.
     */
    @Test
    public void theFeInitCallCreatesTheDefaultWarehouseAndStartsTheWatchOnce() {
        Assertions.assertTrue(manager.warehouseExists("default_warehouse"));
        Assertions.assertInstanceOf(DefaultWarehouse.class, manager.getWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_ID));
        Assertions.assertEquals(new HashSet<>(Arrays.asList(LostWarehouseWatch.NAME, StarMgrSync.NAME)),
                fe.periodicTasks.keySet());

        manager.initDefaultWarehouse();
        new UserWarehouseManager(fe).initDefaultWarehouse();

        Assertions.assertEquals(2, fe.periodicTasks.size(), "each task once, however many managers start");
        Assertions.assertEquals(Collections.singletonList(0L), manager.getAllWarehouseIds());
    }

    // ------------------------------------------------------------------ CREATE

    @Test
    public void createAddsAWarehouseWithItsOwnWorkerGroup() throws Exception {
        manager.createWarehouse(create("research", false, "ad hoc research"));

        UserWarehouse research = user("research");
        Assertions.assertEquals(10001L, research.getId());
        Assertions.assertEquals("research", research.getName());
        Assertions.assertEquals("ad hoc research", research.getComment());
        Assertions.assertEquals(501L, research.getWorkerGroupId());
        Assertions.assertEquals(1, research.getReplicaNumber());
        Assertions.assertEquals(fe.clock, research.getCreateTimeMs());
        Assertions.assertEquals(fe.clock, research.getUpdateTimeMs());

        Assertions.assertEquals(Collections.singletonMap(501L, 1), fe.workerGroups);
        Assertions.assertEquals(Collections.singletonList(OperationType.OP_CREATE_WAREHOUSE), fe.journaledOperations());
        Assertions.assertTrue(fe.journal.get(0).json.contains("\"clazz\":\"" + WarehouseSubtypeRegistration.LABEL + "\""),
                fe.journal.get(0).json);

        // Everything the stock manager offers by id or name now works for the new warehouse.
        Assertions.assertSame(research, manager.getWarehouse(10001L));
        Assertions.assertTrue(manager.warehouseExists("research"));
        Assertions.assertEquals(2, manager.getAllWorkerGroupCount());
        Assertions.assertEquals(new HashSet<>(Arrays.asList("default_warehouse", "research")),
                manager.getAllWarehouseNames());
    }

    @Test
    public void createPassesTheReplicaNumberToStarMgr() throws Exception {
        manager.createWarehouse(create("etl", false, null, "replica_number", "3"));

        Assertions.assertEquals(3, user("etl").getReplicaNumber());
        Assertions.assertEquals(Collections.singletonMap(501L, 3), fe.workerGroups);
    }

    @Test
    public void createOfAnExistingWarehouseFailsUnlessIfNotExists() throws Exception {
        manager.createWarehouse(create("research"));
        int journalSize = fe.journal.size();

        String message = messageOf(DdlException.class, () -> manager.createWarehouse(create("research")));
        Assertions.assertTrue(message.contains("research already exists"), message);

        manager.createWarehouse(create("research", true, null));

        Assertions.assertEquals(1, fe.workerGroups.size());
        Assertions.assertEquals(journalSize, fe.journal.size());
    }

    @Test
    public void createNeedsSharedDataMode() {
        fe.sharedData = false;

        String message = messageOf(DdlException.class, () -> manager.createWarehouse(create("research")));

        Assertions.assertTrue(message.contains("shared-data"), message);
        Assertions.assertFalse(manager.warehouseExists("research"));
        Assertions.assertEquals(0, fe.starMgrCalls);
    }

    @Test
    public void createIsRefusedWhenTheSettingIsOff() throws Exception {
        manager.createWarehouse(create("existing"));
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());
        int starMgrCalls = fe.starMgrCalls;

        String message = messageOf(DdlException.class, () -> manager.createWarehouse(create("fresh")));
        Assertions.assertTrue(message.contains("Creating warehouses is disabled"), message);
        Assertions.assertTrue(message.contains(WarehouseExtensionSettings.KEY_ENABLED), message);
        Assertions.assertFalse(manager.warehouseExists("fresh"));
        Assertions.assertEquals(starMgrCalls, fe.starMgrCalls);

        // IF NOT EXISTS on a missing warehouse would have to create it, so it is refused as well.
        Assertions.assertThrows(DdlException.class, () -> manager.createWarehouse(create("fresh", true, null)));
    }

    /**
     * The Kubernetes operator runs CREATE WAREHOUSE IF NOT EXISTS on every compute node start. With the setting
     * off that must stay a no-op for a warehouse that exists, and a plain CREATE must still say "exists".
     */
    @Test
    public void existingWarehousesAreUntouchedByTheSetting() throws Exception {
        manager.createWarehouse(create("existing"));
        WarehouseExtensionSettings.install(WarehouseExtensionSettings.defaults());

        manager.createWarehouse(create("existing", true, null));
        String message = messageOf(DdlException.class, () -> manager.createWarehouse(create("existing")));
        Assertions.assertTrue(message.contains("already exists"), message);

        manager.alterWarehouse(alter("existing", "replica_number", "2"));
        Assertions.assertEquals(2, user("existing").getReplicaNumber());
        manager.dropWarehouse(new DropWarehouseStmt(false, "existing"));
        Assertions.assertFalse(manager.warehouseExists("existing"));
    }

    @Test
    public void createRejectsANameThatDiffersOnlyByCase() throws Exception {
        manager.createWarehouse(create("research"));

        String message = messageOf(DdlException.class, () -> manager.createWarehouse(create("Research")));
        Assertions.assertTrue(message.contains("differs only by case"), message);
        Assertions.assertTrue(message.contains("'research'"), message);

        message = messageOf(DdlException.class, () -> manager.createWarehouse(create("DEFAULT_WAREHOUSE")));
        Assertions.assertTrue(message.contains("'default_warehouse'"), message);

        Assertions.assertEquals(1, fe.workerGroups.size());
    }

    @Test
    public void createValidatesPropertiesBeforeTouchingStarMgr() {
        Assertions.assertThrows(DdlException.class,
                () -> manager.createWarehouse(create("research", false, null, "replica_number", "0")));
        Assertions.assertThrows(DdlException.class,
                () -> manager.createWarehouse(create("research", false, null, "size", "x1")));

        Assertions.assertEquals(0, fe.starMgrCalls);
        Assertions.assertFalse(manager.warehouseExists("research"));
    }

    @Test
    public void createFailsCleanlyWhenStarMgrRefuses() {
        fe.failCreateWorkerGroup = new DdlException("Failed to create worker group. error: no quota");

        String message = messageOf(DdlException.class, () -> manager.createWarehouse(create("research")));

        Assertions.assertTrue(message.contains("no quota"), message);
        Assertions.assertFalse(manager.warehouseExists("research"));
        Assertions.assertTrue(fe.journal.isEmpty());
    }

    @Test
    public void createReleasesTheWorkerGroupWhenTheEditLogFails() {
        fe.failJournal = new IllegalStateException("not the leader any more");

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
                () -> manager.createWarehouse(create("research")));

        Assertions.assertEquals("not the leader any more", e.getMessage());
        Assertions.assertFalse(manager.warehouseExists("research"));
        Assertions.assertTrue(fe.workerGroups.isEmpty());
        Assertions.assertEquals(Collections.singletonList(501L), fe.deletedWorkerGroups);
    }

    /**
     * The cleanup after a failed create is best effort. Whatever it throws, the caller sees the original error.
     */
    @Test
    public void createKeepsTheOriginalErrorWhenCleanupFailsToo() {
        fe.failJournal = new IllegalStateException("not the leader any more");

        fe.failDeleteWorkerGroup = new DdlException("StarMgr unreachable");
        Assertions.assertEquals("not the leader any more", messageOf(IllegalStateException.class,
                () -> manager.createWarehouse(create("first"))));

        fe.failDeleteWorkerGroup = new IllegalStateException("StarMgr client closed");
        Assertions.assertEquals("not the leader any more", messageOf(IllegalStateException.class,
                () -> manager.createWarehouse(create("second"))));

        Assertions.assertEquals(Collections.singleton("default_warehouse"), manager.getAllWarehouseNames());
    }

    // ------------------------------------------------------------------ ALTER

    @Test
    public void alterChangesTheReplicaNumberAndKeepsEverythingElse() throws Exception {
        manager.createWarehouse(create("etl", false, "nightly loads"));
        UserWarehouse before = user("etl");
        fe.clock += 60_000L;

        manager.alterWarehouse(alter("etl", "replica_number", "2"));

        UserWarehouse after = user("etl");
        Assertions.assertNotSame(before, after);
        Assertions.assertEquals(1, before.getReplicaNumber(), "the old object must not change under readers");
        Assertions.assertEquals(2, after.getReplicaNumber());
        Assertions.assertEquals(before.getId(), after.getId());
        Assertions.assertEquals(before.getWorkerGroupId(), after.getWorkerGroupId());
        Assertions.assertEquals("nightly loads", after.getComment());
        Assertions.assertEquals(before.getCreateTimeMs(), after.getCreateTimeMs());
        Assertions.assertEquals(fe.clock, after.getUpdateTimeMs());
        Assertions.assertSame(after, manager.getWarehouse(after.getId()));

        Assertions.assertEquals(Collections.singletonMap(501L, 2), fe.workerGroups);
        Assertions.assertEquals(Arrays.asList(OperationType.OP_CREATE_WAREHOUSE, OperationType.OP_ALTER_WAREHOUSE),
                fe.journaledOperations());
    }

    /**
     * An ALTER that changes nothing leaves StarMgr alone but still writes the warehouse to the edit log. That is
     * the repair for lost metadata: the FE that kept the warehouse writes it back for the FEs that lost it.
     */
    @Test
    public void alterToTheSameValueWritesTheWarehouseBackToTheEditLog() throws Exception {
        manager.createWarehouse(create("etl", false, "nightly loads", "replica_number", "2"));
        UserWarehouse before = user("etl");
        int starMgrCalls = fe.starMgrCalls;

        manager.alterWarehouse(alter("etl", "replica_number", "2"));

        Assertions.assertSame(before, user("etl"));
        Assertions.assertEquals(starMgrCalls + 1, fe.starMgrCalls, "StarMgr is asked once and not changed");
        Assertions.assertEquals(Collections.singletonMap(before.getWorkerGroupId(), 2), fe.workerGroups);
        Assertions.assertEquals(Arrays.asList(OperationType.OP_CREATE_WAREHOUSE, OperationType.OP_ALTER_WAREHOUSE),
                fe.journaledOperations());

        // An FE that lost the warehouse sees only that last entry, and gets the warehouse back from it.
        FakeFeEnvironment.JournalEntry writtenBack = fe.journal.get(1);
        FakeFeEnvironment lostFe = new FakeFeEnvironment();
        UserWarehouseManager lost = new UserWarehouseManager(lostFe);
        lost.initDefaultWarehouse();
        lost.replayAlterWarehouse(com.starrocks.persist.gson.GsonUtils.GSON.fromJson(writtenBack.json, Warehouse.class));

        UserWarehouse restored = (UserWarehouse) lost.getWarehouse("etl");
        Assertions.assertEquals(before.getId(), restored.getId());
        Assertions.assertEquals(before.getWorkerGroupId(), restored.getWorkerGroupId());
        Assertions.assertEquals(2, restored.getReplicaNumber());
        Assertions.assertEquals("nightly loads", restored.getComment());
        Assertions.assertEquals(0, lostFe.starMgrCalls);
    }

    /**
     * An FE that dies between the StarMgr update and the edit log leaves StarMgr with a number nobody committed.
     * Repeating the stored value is how an operator puts it right at once.
     */
    @Test
    public void alterToTheSameValueSetsStarMgrBackToTheStoredNumber() throws Exception {
        manager.createWarehouse(create("etl", false, null, "replica_number", "2"));
        long workerGroupId = user("etl").getWorkerGroupId();
        fe.workerGroups.put(workerGroupId, 5);

        manager.alterWarehouse(alter("etl", "replica_number", "2"));

        Assertions.assertEquals(Collections.singletonMap(workerGroupId, 2), fe.workerGroups);
        Assertions.assertEquals(2, user("etl").getReplicaNumber());
        Assertions.assertEquals(Arrays.asList(OperationType.OP_CREATE_WAREHOUSE, OperationType.OP_ALTER_WAREHOUSE),
                fe.journaledOperations());
    }

    /**
     * StarMgr comes first here too. A statement that cannot check or correct it fails and writes nothing.
     */
    @Test
    public void alterToTheSameValueWritesNothingWhenStarMgrRefuses() throws Exception {
        manager.createWarehouse(create("etl", false, null, "replica_number", "2"));
        long workerGroupId = user("etl").getWorkerGroupId();

        fe.failReadWorkerGroup = new DdlException("Failed to get worker group info (id=" + workerGroupId + ")");
        String message = messageOf(DdlException.class, () -> manager.alterWarehouse(alter("etl", "replica_number", "2")));
        Assertions.assertTrue(message.contains("Failed to get worker group info"), message);

        fe.failReadWorkerGroup = null;
        fe.workerGroups.put(workerGroupId, 5);
        fe.failUpdateWorkerGroup = new DdlException("Failed to update worker group. error: timeout");
        Assertions.assertThrows(DdlException.class, () -> manager.alterWarehouse(alter("etl", "replica_number", "2")));

        Assertions.assertEquals(1, fe.journal.size(), "nothing but the CREATE is in the edit log");
    }

    @Test
    public void alterRejectsWhatItCannotDo() throws Exception {
        manager.createWarehouse(create("etl"));

        String message = messageOf(DdlException.class, () -> manager.alterWarehouse(alter("nosuch", "replica_number", "2")));
        Assertions.assertTrue(message.contains("nosuch"), message);

        message = messageOf(DdlException.class,
                () -> manager.alterWarehouse(alter("default_warehouse", "replica_number", "2")));
        Assertions.assertTrue(message.contains("built in") && message.contains("altered"), message);

        Assertions.assertThrows(DdlException.class, () -> manager.alterWarehouse(alter("etl")));
        Assertions.assertThrows(DdlException.class, () -> manager.alterWarehouse(alter("etl", "comment", "x")));
        Assertions.assertThrows(DdlException.class, () -> manager.alterWarehouse(alter("etl", "replica_number", "-1")));

        fe.sharedData = false;
        Assertions.assertThrows(DdlException.class, () -> manager.alterWarehouse(alter("etl", "replica_number", "2")));

        Assertions.assertEquals(1, user("etl").getReplicaNumber());
        Assertions.assertEquals(1, fe.journal.size());
    }

    @Test
    public void alterWritesNothingWhenStarMgrRefuses() throws Exception {
        manager.createWarehouse(create("etl"));
        fe.failUpdateWorkerGroup = new DdlException("Failed to update worker group. error: timeout");

        String message = messageOf(DdlException.class, () -> manager.alterWarehouse(alter("etl", "replica_number", "2")));

        Assertions.assertTrue(message.contains("timeout"), message);
        Assertions.assertEquals(1, user("etl").getReplicaNumber());
        Assertions.assertEquals(1, fe.journal.size());
    }

    @Test
    public void alterPutsStarMgrBackWhenTheEditLogFails() throws Exception {
        manager.createWarehouse(create("etl"));
        fe.failJournal = new IllegalStateException("not the leader any more");

        Assertions.assertThrows(IllegalStateException.class,
                () -> manager.alterWarehouse(alter("etl", "replica_number", "2")));

        Assertions.assertEquals(1, user("etl").getReplicaNumber());
        Assertions.assertEquals(Collections.singletonMap(501L, 1), fe.workerGroups);
    }

    @Test
    public void alterKeepsTheOriginalErrorWhenPuttingStarMgrBackFails() throws Exception {
        manager.createWarehouse(create("etl"));
        fe.failJournal = new IllegalStateException("not the leader any more");

        // The update to 2 works, the one back to 1 throws a RuntimeException.
        fe.onlyAllowedReplicaNumber = 2;
        Assertions.assertEquals("not the leader any more", messageOf(IllegalStateException.class,
                () -> manager.alterWarehouse(alter("etl", "replica_number", "2"))));
        Assertions.assertEquals(1, user("etl").getReplicaNumber());

        // The same with a DdlException on the way back.
        manager = new UserWarehouseManager(new FakeFeEnvironment() {
            private int updates;

            @Override
            public void updateWorkerGroup(long workerGroupId, int replicaNumber) throws DdlException {
                if (++updates > 1) {
                    throw new DdlException("StarMgr unreachable");
                }
            }

            @Override
            public void writeJournal(short operation, Object entry, com.starrocks.persist.WALApplier afterWrite) {
                if (operation == OperationType.OP_ALTER_WAREHOUSE) {
                    throw new IllegalStateException("not the leader any more");
                }
                super.writeJournal(operation, entry, afterWrite);
            }
        });
        manager.initDefaultWarehouse();
        manager.createWarehouse(create("etl"));
        Assertions.assertEquals("not the leader any more", messageOf(IllegalStateException.class,
                () -> manager.alterWarehouse(alter("etl", "replica_number", "2"))));
        Assertions.assertEquals(1, user("etl").getReplicaNumber());
    }

    // ------------------------------------------------------------------ DROP

    @Test
    public void dropRemovesTheWarehouseAndItsWorkerGroup() throws Exception {
        manager.createWarehouse(create("research"));
        UserWarehouse research = user("research");

        manager.dropWarehouse(new DropWarehouseStmt(false, "research"));

        Assertions.assertFalse(manager.warehouseExists("research"));
        Assertions.assertFalse(manager.warehouseExists(research.getId()));
        Assertions.assertTrue(fe.workerGroups.isEmpty());
        Assertions.assertEquals(Collections.singletonList(501L), fe.deletedWorkerGroups);
        Assertions.assertEquals(Arrays.asList(OperationType.OP_CREATE_WAREHOUSE, OperationType.OP_DROP_WAREHOUSE),
                fe.journaledOperations());
        // A statement that still holds the old object can no longer add a node to it.
        Assertions.assertTrue(research.isRetired());
        Assertions.assertThrows(DdlException.class, () -> research.addNodeToCNGroup(new ComputeNode(1L, "h", 9050), ""));
    }

    @Test
    public void dropOfAMissingWarehouseFailsUnlessIfExists() throws Exception {
        String message = messageOf(DdlException.class, () -> manager.dropWarehouse(new DropWarehouseStmt(false, "nosuch")));
        Assertions.assertTrue(message.contains("nosuch"), message);

        manager.dropWarehouse(new DropWarehouseStmt(true, "nosuch"));

        Assertions.assertTrue(fe.journal.isEmpty());
        Assertions.assertEquals(0, fe.starMgrCalls);
    }

    @Test
    public void dropRejectsTheDefaultWarehouseAndSharedNothing() throws Exception {
        String message = messageOf(DdlException.class,
                () -> manager.dropWarehouse(new DropWarehouseStmt(false, "default_warehouse")));
        Assertions.assertTrue(message.contains("built in") && message.contains("dropped"), message);
        Assertions.assertTrue(manager.warehouseExists("default_warehouse"));

        manager.createWarehouse(create("research"));
        fe.sharedData = false;
        Assertions.assertThrows(DdlException.class, () -> manager.dropWarehouse(new DropWarehouseStmt(false, "research")));
        Assertions.assertTrue(manager.warehouseExists("research"));
    }

    @Test
    public void dropIsRefusedWhileTheWarehouseHasNodes() throws Exception {
        manager.createWarehouse(create("research"));
        UserWarehouse research = user("research");
        fe.addNode(20002L, "cn-b", research, false);
        fe.addNode(20001L, "cn-a", research, true);

        String message = messageOf(DdlException.class, () -> manager.dropWarehouse(new DropWarehouseStmt(false, "research")));

        Assertions.assertTrue(message.startsWith("Cannot drop warehouse research: 2 node(s) are registered in it"
                + " (cn-a:9050, cn-b:9050)."), message);
        Assertions.assertTrue(message.contains("ALTER SYSTEM DROP COMPUTE NODE '<host>:<heartbeat port>'"
                + " FROM WAREHOUSE research"), message);
        Assertions.assertTrue(manager.warehouseExists("research"));
        Assertions.assertEquals(1, fe.journal.size());
        Assertions.assertFalse(fe.workerGroups.isEmpty());
        // The refused drop must leave the warehouse open for more nodes.
        Assertions.assertFalse(research.isRetired());
        fe.addNode(20003L, "cn-c", research, true);

        fe.nodes.clear();
        manager.dropWarehouse(new DropWarehouseStmt(false, "research"));
        Assertions.assertFalse(manager.warehouseExists("research"));
    }

    /**
     * A resource group stores warehouse names. Dropping a warehouse that a group still names would leave the
     * group pointing at nothing, and later at any new warehouse that gets the same name.
     */
    @Test
    public void dropIsRefusedWhileAResourceGroupNamesTheWarehouse() throws Exception {
        manager.createWarehouse(create("research"));
        UserWarehouse research = user("research");
        fe.resourceGroups.put("rg_reports", Arrays.asList("etl", "research"));
        fe.resourceGroups.put("rg_adhoc", Collections.singletonList("research"));
        fe.resourceGroups.put("rg_etl", Collections.singletonList("etl"));
        int starMgrCallsBefore = fe.starMgrCalls;

        String message = messageOf(DdlException.class, () -> manager.dropWarehouse(new DropWarehouseStmt(false, "research")));

        Assertions.assertTrue(message.startsWith("Cannot drop warehouse research: resource group(s) rg_adhoc, rg_reports"
                + " name it in their 'warehouses' property."), message);
        Assertions.assertTrue(message.contains("ALTER RESOURCE GROUP <group> WITH ('warehouses' ="), message);
        Assertions.assertFalse(message.contains("rg_etl"), message);
        Assertions.assertTrue(manager.warehouseExists("research"));
        Assertions.assertEquals(1, fe.journal.size(), "nothing but the CREATE is in the edit log");
        Assertions.assertEquals(starMgrCallsBefore, fe.starMgrCalls);
        // The refused drop must leave the warehouse open for nodes.
        Assertions.assertFalse(research.isRetired());

        // Even with the IF EXISTS form: the warehouse exists, so the statement is a real drop.
        Assertions.assertThrows(DdlException.class, () -> manager.dropWarehouse(new DropWarehouseStmt(true, "research")));

        fe.resourceGroups.put("rg_reports", Collections.singletonList("etl"));
        fe.resourceGroups.remove("rg_adhoc");
        manager.dropWarehouse(new DropWarehouseStmt(false, "research"));
        Assertions.assertFalse(manager.warehouseExists("research"));
    }

    /**
     * The FE matches a session's warehouse against a resource group's names exactly. A name in another case
     * never selected this warehouse, so it does not hold the drop back.
     */
    @Test
    public void aResourceGroupNamingAnotherCaseDoesNotBlockTheDrop() throws Exception {
        manager.createWarehouse(create("research"));
        fe.resourceGroups.put("rg_old", Collections.singletonList("Research"));

        manager.dropWarehouse(new DropWarehouseStmt(false, "research"));

        Assertions.assertFalse(manager.warehouseExists("research"));
    }

    /**
     * With nodes and a resource group both in the way, the nodes are reported first.
     */
    @Test
    public void dropReportsNodesBeforeResourceGroups() throws Exception {
        manager.createWarehouse(create("research"));
        fe.addNode(20001L, "cn-a", user("research"), true);
        fe.resourceGroups.put("rg_adhoc", Collections.singletonList("research"));

        String message = messageOf(DdlException.class, () -> manager.dropWarehouse(new DropWarehouseStmt(false, "research")));

        Assertions.assertTrue(message.contains("1 node(s) are registered in it"), message);
        Assertions.assertFalse(message.contains("rg_adhoc"), message);
    }

    @Test
    public void dropLeavesTheWarehouseUsableWhenTheEditLogFails() throws Exception {
        manager.createWarehouse(create("research"));
        UserWarehouse research = user("research");
        fe.failJournal = new IllegalStateException("not the leader any more");

        Assertions.assertThrows(IllegalStateException.class,
                () -> manager.dropWarehouse(new DropWarehouseStmt(false, "research")));

        Assertions.assertTrue(manager.warehouseExists("research"));
        Assertions.assertFalse(research.isRetired());
        Assertions.assertEquals(Collections.singletonMap(501L, 1), fe.workerGroups);
    }

    /**
     * Once the drop is in the edit log the warehouse is gone, even if StarMgr cannot delete the group. The group
     * is then an orphan in StarMgr, which is logged.
     */
    @Test
    public void dropSucceedsEvenWhenTheWorkerGroupCannotBeDeleted() throws Exception {
        manager.createWarehouse(create("research"));
        fe.failDeleteWorkerGroup = new DdlException("StarMgr unreachable");

        manager.dropWarehouse(new DropWarehouseStmt(false, "research"));

        Assertions.assertFalse(manager.warehouseExists("research"));
        Assertions.assertEquals(Collections.singletonMap(501L, 1), fe.workerGroups);
    }

    // ------------------------------------------------------------------ not supported

    @Test
    public void suspendAndResumeAreNotSupported() {
        String message = messageOf(DdlException.class, () -> manager.suspendWarehouse(new SuspendWarehouseStmt("research")));
        Assertions.assertTrue(message.contains("SUSPEND WAREHOUSE is not supported"), message);
        message = messageOf(DdlException.class, () -> manager.resumeWarehouse(new ResumeWarehouseStmt("research")));
        Assertions.assertTrue(message.contains("RESUME WAREHOUSE is not supported"), message);
    }

    // ------------------------------------------------------------------ replay

    /**
     * A follower FE, and the checkpoint thread, see only the edit log. They must end up with the same
     * warehouses as the leader without ever calling StarMgr.
     */
    @Test
    public void aFollowerReplaysTheEditLogWithoutCallingStarMgr() throws Exception {
        manager.createWarehouse(create("research", false, "ad hoc research"));
        manager.createWarehouse(create("etl", false, null, "replica_number", "2"));
        manager.createWarehouse(create("scratch"));
        manager.alterWarehouse(alter("etl", "replica_number", "3"));
        manager.dropWarehouse(new DropWarehouseStmt(false, "scratch"));

        FakeFeEnvironment followerFe = new FakeFeEnvironment();
        UserWarehouseManager follower = new UserWarehouseManager(followerFe);
        follower.initDefaultWarehouse();
        fe.replayInto(follower);

        Assertions.assertEquals(manager.getAllWarehouseNames(), follower.getAllWarehouseNames());
        Assertions.assertEquals(new HashSet<>(Arrays.asList("default_warehouse", "research", "etl")),
                follower.getAllWarehouseNames());
        for (String name : Arrays.asList("research", "etl")) {
            UserWarehouse leaderCopy = user(name);
            UserWarehouse followerCopy = (UserWarehouse) follower.getWarehouse(name);
            Assertions.assertNotSame(leaderCopy, followerCopy);
            Assertions.assertEquals(leaderCopy.getId(), followerCopy.getId());
            Assertions.assertEquals(leaderCopy.getComment(), followerCopy.getComment());
            Assertions.assertEquals(leaderCopy.getWorkerGroupId(), followerCopy.getWorkerGroupId());
            Assertions.assertEquals(leaderCopy.getReplicaNumber(), followerCopy.getReplicaNumber());
            Assertions.assertEquals(leaderCopy.getCreateTimeMs(), followerCopy.getCreateTimeMs());
            Assertions.assertEquals(leaderCopy.getUpdateTimeMs(), followerCopy.getUpdateTimeMs());
        }
        Assertions.assertEquals(3, ((UserWarehouse) follower.getWarehouse("etl")).getReplicaNumber());
        Assertions.assertEquals(0, followerFe.starMgrCalls);
        Assertions.assertTrue(followerFe.journal.isEmpty());

        // The replayed warehouses are wired to the follower's own FE: SHOW WAREHOUSES works there.
        followerFe.addNode(30001L, "cn-x", follower.getWarehouse("research"), true);
        Assertions.assertEquals(WarehouseRows.STATE_AVAILABLE, follower.getWarehouse("research").getWarehouseInfo().get(2));
    }

    @Test
    public void replayOfADropForAnUnknownWarehouseIsIgnored() {
        manager.replayDropWarehouse(new com.starrocks.persist.DropWarehouseLog("never_existed"));

        Assertions.assertEquals(Collections.singleton("default_warehouse"), manager.getAllWarehouseNames());
    }

    /**
     * The manager keeps whatever warehouse type arrives. Only user warehouses get wired to the FE and retired.
     */
    @Test
    public void foreignWarehouseTypesPassThroughReplay() {
        Warehouse foreign = new DefaultWarehouse(77L, "foreign");

        manager.replayCreateWarehouse(foreign);
        Assertions.assertSame(foreign, manager.getWarehouse("foreign"));

        manager.replayDropWarehouse(new com.starrocks.persist.DropWarehouseLog("foreign"));
        Assertions.assertFalse(manager.warehouseExists("foreign"));
        Assertions.assertFalse(manager.warehouseExists(77L));
    }

    // ------------------------------------------------------------------ image

    private static byte[] saveImage(WarehouseManager source) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter imageWriter = new ImageWriter("unused", 0L);
        imageWriter.setOutputStream(bytes);
        source.save(imageWriter);
        return bytes.toByteArray();
    }

    @Test
    public void theImageRoundTripsThroughTheRealImageFormat() throws Exception {
        manager.createWarehouse(create("research", false, "ad hoc research"));
        manager.createWarehouse(create("etl", false, null, "replica_number", "2"));
        fe.clock += 1_000L;
        manager.alterWarehouse(alter("research", "replica_number", "4"));

        byte[] image = saveImage(manager);

        FakeFeEnvironment restartedFe = new FakeFeEnvironment();
        UserWarehouseManager restarted = new UserWarehouseManager(restartedFe);
        JsonReader jsonReader = new JsonReader(new InputStreamReader(new ByteArrayInputStream(image), StandardCharsets.UTF_8));
        jsonReader.setLenient(true);
        SRMetaBlockReader reader = new SRMetaBlockReaderV2(jsonReader);
        restarted.load(reader);
        reader.close();

        Assertions.assertEquals(new HashSet<>(Arrays.asList("default_warehouse", "research", "etl")),
                restarted.getAllWarehouseNames());
        UserWarehouse research = (UserWarehouse) restarted.getWarehouse("research");
        Assertions.assertEquals(user("research").getId(), research.getId());
        Assertions.assertEquals("ad hoc research", research.getComment());
        Assertions.assertEquals(4, research.getReplicaNumber());
        Assertions.assertEquals(user("research").getWorkerGroupId(), research.getWorkerGroupId());
        Assertions.assertEquals(user("research").getUpdateTimeMs(), research.getUpdateTimeMs());
        Assertions.assertEquals(2, ((UserWarehouse) restarted.getWarehouse("etl")).getReplicaNumber());
        Assertions.assertEquals(0, restartedFe.starMgrCalls);
        // Loaded warehouses are wired to the FE they were loaded into.
        Assertions.assertEquals("0", research.getWarehouseInfo().get(3));

        // Warehouses are written in id order, so two FEs with the same state write the same image.
        String text = new String(image, StandardCharsets.UTF_8);
        Assertions.assertTrue(text.indexOf("\"research\"") < text.indexOf("\"etl\""), text);
        Assertions.assertFalse(text.contains("default_warehouse"), "the default warehouse is not persisted");
    }

    /**
     * With no user warehouse the image is byte for byte what the stock manager writes: nothing. So removing
     * the jar after every warehouse has been dropped loses nothing.
     */
    @Test
    public void anImageWithoutUserWarehousesIsTheStockImage() throws Exception {
        Assertions.assertEquals(0, saveImage(manager).length);

        manager.createWarehouse(create("research"));
        Assertions.assertTrue(saveImage(manager).length > 0);

        manager.dropWarehouse(new DropWarehouseStmt(false, "research"));
        Assertions.assertArrayEquals(saveImage(new WarehouseManager()), saveImage(manager));
    }

    // ------------------------------------------------------------------ nodes

    @Test
    public void nodesJoinTheWorkerGroupOfTheirWarehouse() throws Exception {
        manager.createWarehouse(create("research"));
        manager.createWarehouse(create("etl"));

        ComputeNode a = fe.addNode(20001L, "cn-a", manager.getWarehouse("research"), true);
        ComputeNode b = fe.addNode(20002L, "cn-b", manager.getWarehouse("etl"), true);

        Assertions.assertEquals(user("research").getWorkerGroupId(), a.getWorkerGroupId());
        Assertions.assertEquals(user("research").getId(), a.getWarehouseId());
        Assertions.assertEquals(user("etl").getWorkerGroupId(), b.getWorkerGroupId());
        Assertions.assertNotEquals(a.getWorkerGroupId(), b.getWorkerGroupId());

        List<List<String>> rows = manager.getWarehouse("research").getWarehouseNodesInfo();
        Assertions.assertEquals(1, rows.size());
        Assertions.assertEquals("cn-a", rows.get(0).get(5));
    }
}
