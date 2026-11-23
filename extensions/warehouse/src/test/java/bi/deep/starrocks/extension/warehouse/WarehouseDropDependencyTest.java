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

import com.starrocks.catalog.Database;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.common.DdlException;
import com.starrocks.common.MetaNotFoundException;
import com.starrocks.lake.StarOSAgent;
import com.starrocks.load.routineload.KafkaRoutineLoadJob;
import com.starrocks.load.routineload.RoutineLoadJob.JobState;
import com.starrocks.load.routineload.RoutineLoadMgr;
import com.starrocks.persist.EditLog;
import com.starrocks.persist.OperationType;
import com.starrocks.persist.WALApplier;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.LocalMetastore;
import com.starrocks.server.RunMode;
import com.starrocks.sql.ast.KeysType;
import com.starrocks.sql.ast.warehouse.DropWarehouseStmt;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mocked;
import mockit.Verifications;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.stream.Stream;

public class WarehouseDropDependencyTest {
    @Mocked
    private GlobalStateMgr state;
    @Mocked
    private RunMode runMode;
    @Injectable
    private EditLog editLog;
    @Injectable
    private StarOSAgent starMgr;

    private final Database db = new Database(20001L, "analytics");
    private final RoutineLoadMgr loads = new RoutineLoadMgr();
    private final MultiWarehouseManager manager = new MultiWarehouseManager(FeEnvironment.live());
    private final UserDefinedWarehouse warehouse = new UserDefinedWarehouse(10001L, "research", "", 501L, 1, 0L, 0L);

    @BeforeEach
    public void setUp() {
        LocalMetastore metastore = new LocalMetastore(state, null, null);
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState();
                result = state;
                state.getLocalMetastore();
                result = metastore;
                minTimes = 0;
                state.getRoutineLoadMgr();
                result = loads;
                minTimes = 0;
                state.getWarehouseMgr();
                result = manager;
                minTimes = 0;
                state.getEditLog();
                result = editLog;
                minTimes = 0;
                state.getStarOSAgent();
                result = starMgr;
                minTimes = 0;
                RunMode.isSharedDataMode();
                result = true;
                state.getNodeMgr().getClusterInfo().backendAndComputeNodeStream();
                result = Stream.empty();
                minTimes = 0;
                state.getResourceGroupMgr().getResourceGroupIds();
                result = List.of();
                minTimes = 0;
            }
        };
        metastore.unprotectCreateDb(db);
        manager.replayCreateWarehouse(warehouse);
    }

    @Test
    public void materializedViewBlocksDropByWarehouseId() throws Exception {
        addMaterializedView(warehouse.getId());
        assertDropRejected("daily_sales");
    }

    @Test
    public void materializedViewInDroppedDatabaseDoesNotBlockDrop() throws Exception {
        addMaterializedView(warehouse.getId());
        // The scan's database snapshot still contains the database after a concurrent drop marks it gone.
        db.setExist(false);

        manager.dropWarehouse(new DropWarehouseStmt(false, warehouse.getName()));
        verifyDropWrites(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobState.class, names = {"NEED_SCHEDULE", "RUNNING", "PAUSED"})
    public void nonterminalRoutineLoadBlocksDropByWarehouseId(JobState jobState) throws Exception {
        addRoutineLoad(40001L, "sales_ingest", warehouse.getId(), jobState);
        assertDropRejected("sales_ingest");
    }

    @Test
    public void routineLoadLookupFailureRejectsDropAndPreservesCause() throws Exception {
        MetaNotFoundException failure = new MetaNotFoundException("Routine Load metadata unavailable");
        new Expectations(loads) {
            {
                loads.getJob(null, null, false);
                result = failure;
            }
        };

        DdlException error = assertDropRejected("Routine Load");
        Assertions.assertTrue(error.getMessage().contains(Long.toString(warehouse.getId())), error.getMessage());
        Assertions.assertSame(failure, error.getCause());
    }

    @Test
    public void terminalJobsAndReferencesToAnotherWarehouseAllowDrop() throws Exception {
        UserDefinedWarehouse other = new UserDefinedWarehouse(10002L, "etl", "", 502L, 1, 0L, 0L);
        manager.replayCreateWarehouse(other);
        addMaterializedView(other.getId());
        addRoutineLoad(40001L, "etl_ingest", other.getId(), JobState.RUNNING);
        addRoutineLoad(40002L, "stopped_ingest", warehouse.getId(), JobState.STOPPED);
        addRoutineLoad(40003L, "cancelled_ingest", warehouse.getId(), JobState.CANCELLED);

        manager.dropWarehouse(new DropWarehouseStmt(false, warehouse.getName()));
        verifyDropWrites(1);
    }

    private void addMaterializedView(long warehouseId) {
        MaterializedView mv = new MaterializedView(30001L, db.getId(), "daily_sales", List.of(), KeysType.DUP_KEYS,
                null, null, new MaterializedView.MvRefreshScheme());
        mv.setWarehouseId(warehouseId);
        Assertions.assertTrue(db.registerTableUnlocked(mv));
    }

    private void addRoutineLoad(long id, String name, long warehouseId, JobState jobState) {
        KafkaRoutineLoadJob job = new KafkaRoutineLoadJob(id, name, db.getId(), 30002L, "broker:9092", "sales");
        job.setWarehouseId(warehouseId);
        loads.replayCreateRoutineLoadJob(job);
        job.replayUpdateState(jobState, null);
    }

    private DdlException assertDropRejected(String messageFragment) throws Exception {
        DdlException error = Assertions.assertThrows(DdlException.class,
                () -> manager.dropWarehouse(new DropWarehouseStmt(false, warehouse.getName())));
        Assertions.assertTrue(error.getMessage().contains(messageFragment), error.getMessage());
        Assertions.assertSame(warehouse, manager.getWarehouse(warehouse.getId()));
        Assertions.assertFalse(warehouse.isRetired());
        verifyDropWrites(0);
        return error;
    }

    private void verifyDropWrites(int expected) throws DdlException {
        new Verifications() {
            {
                editLog.logJsonObject(OperationType.OP_DROP_WAREHOUSE, any, (WALApplier) any);
                times = expected;
                starMgr.deleteWorkerGroup(warehouse.getWorkerGroupId());
                times = expected;
            }
        };
    }
}
