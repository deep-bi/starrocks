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

import com.google.gson.annotations.SerializedName;
import com.starrocks.common.DdlException;
import com.starrocks.common.proc.BaseProcResult;
import com.starrocks.common.proc.ProcResult;
import com.starrocks.persist.gson.GsonPostProcessable;
import com.starrocks.sql.ast.warehouse.cngroup.AlterCnGroupStmt;
import com.starrocks.sql.ast.warehouse.cngroup.CreateCnGroupStmt;
import com.starrocks.sql.ast.warehouse.cngroup.DropCnGroupStmt;
import com.starrocks.sql.ast.warehouse.cngroup.EnableDisableCnGroupStmt;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.Warehouse;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * A warehouse created with {@code CREATE WAREHOUSE}, as opposed to the FE's built-in {@code default_warehouse}.
 *
 * <p>It owns one StarMgr worker group. A compute node added to the warehouse joins that group, and the FE
 * schedules a session that selected the warehouse only on the group's nodes.
 *
 * <p>The persisted fields never change after construction. {@code ALTER WAREHOUSE} builds a changed copy with
 * {@link #withReplicaNumber}, writes the copy to the edit log and swaps it in, so readers never see a
 * half-updated warehouse.
 */
public class UserWarehouse extends Warehouse implements GsonPostProcessable {
    /**
     * Version of the persisted layout below. Raise it when a change would make an older jar misread the data;
     * {@link #gsonPostProcess} then stops that older jar.
     */
    static final int FORMAT_VERSION = 1;

    @SerializedName("formatVersion")
    private int formatVersion;
    @SerializedName("workerGroupId")
    private long workerGroupId;
    @SerializedName("replicaNumber")
    private int replicaNumber;
    @SerializedName("createTimeMs")
    private long createTimeMs;
    @SerializedName("updateTimeMs")
    private long updateTimeMs;

    // Not persisted. Null on an instance Gson has just built, until the manager attaches it.
    private transient volatile FeEnvironment env;
    // Not persisted. Set while the warehouse is being dropped and after it is gone.
    private transient volatile boolean retired;

    /**
     * For Gson, which fills the fields afterwards.
     */
    private UserWarehouse() {
        super(0L, null, null);
    }

    UserWarehouse(long id, String name, String comment, long workerGroupId, int replicaNumber,
                  long createTimeMs, long updateTimeMs) {
        super(id, name, comment);
        this.formatVersion = FORMAT_VERSION;
        this.workerGroupId = workerGroupId;
        this.replicaNumber = replicaNumber;
        this.createTimeMs = createTimeMs;
        this.updateTimeMs = updateTimeMs;
    }

    /**
     * @return a copy with another replica number and update time; this instance is left as it is
     */
    UserWarehouse withReplicaNumber(int newReplicaNumber, long nowMs) {
        UserWarehouse copy = new UserWarehouse(getId(), getName(), getComment(), workerGroupId, newReplicaNumber,
                createTimeMs, nowMs);
        copy.env = env;
        return copy;
    }

    public long getWorkerGroupId() {
        return workerGroupId;
    }

    public int getReplicaNumber() {
        return replicaNumber;
    }

    public long getCreateTimeMs() {
        return createTimeMs;
    }

    public long getUpdateTimeMs() {
        return updateTimeMs;
    }

    void attach(FeEnvironment environment) {
        this.env = environment;
    }

    private FeEnvironment env() {
        FeEnvironment attached = env;
        return attached != null ? attached : FeEnvironment.live();
    }

    /**
     * Stops new compute nodes from joining. The manager calls it before it checks that the warehouse is empty.
     * This narrows the window but does not close it: an ADD COMPUTE NODE that passed the retired check just
     * before can still register its node after the drop.
     */
    void retire() {
        retired = true;
    }

    void reinstate() {
        retired = false;
    }

    boolean isRetired() {
        return retired;
    }

    @Override
    public Long getAnyWorkerGroupId() {
        return workerGroupId;
    }

    @Override
    public List<Long> getWorkerGroupIds() {
        return Collections.singletonList(workerGroupId);
    }

    @Override
    public void addNodeToCNGroup(ComputeNode node, String cnGroupName) throws DdlException {
        rejectCnGroupName(cnGroupName);
        if (retired) {
            throw WarehouseErrors.unknownWarehouse(getName());
        }
        node.setWorkerGroupId(workerGroupId);
        node.setWarehouseId(getId());
    }

    @Override
    public void validateRemoveNodeFromCNGroup(ComputeNode node, String cnGroupName) throws DdlException {
        rejectCnGroupName(cnGroupName);
    }

    private static void rejectCnGroupName(String cnGroupName) throws DdlException {
        if (cnGroupName != null && !cnGroupName.isEmpty()) {
            throw WarehouseErrors.cnGroupsNotImplemented();
        }
    }

    /**
     * Always true: the extension has no SUSPEND WAREHOUSE. The FE sends heartbeats to the nodes either way. It
     * uses the flag only to log a failed heartbeat at DEBUG instead of WARN. Whether a node is alive right now
     * is a separate question, answered per query when the FE acquires compute nodes.
     */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public long getResumeTime() {
        return -1L;
    }

    @Override
    public List<String> getWarehouseInfo() {
        return WarehouseRows.summary(this, env().nodesOf(getId()));
    }

    @Override
    public List<List<String>> getWarehouseNodesInfo() {
        FeEnvironment environment = env();
        return WarehouseRows.nodes(this, environment.nodesOf(getId()), environment::workerIdOf);
    }

    @Override
    public ProcResult fetchResult() {
        return new BaseProcResult(WarehouseRows.NODE_COLUMNS, getWarehouseNodesInfo());
    }

    @Override
    public void createCNGroup(CreateCnGroupStmt stmt) throws DdlException {
        throw cnGroupsNotSupported();
    }

    @Override
    public void dropCNGroup(DropCnGroupStmt stmt) throws DdlException {
        throw cnGroupsNotSupported();
    }

    @Override
    public void enableCNGroup(EnableDisableCnGroupStmt stmt) throws DdlException {
        throw cnGroupsNotSupported();
    }

    @Override
    public void disableCNGroup(EnableDisableCnGroupStmt stmt) throws DdlException {
        throw cnGroupsNotSupported();
    }

    @Override
    public void alterCNGroup(AlterCnGroupStmt stmt) throws DdlException {
        throw cnGroupsNotSupported();
    }

    private static DdlException cnGroupsNotSupported() {
        return new DdlException("CN groups are not supported by the warehouse extension");
    }

    @Override
    public void replayInternalOpLog(String payload) {
        // The extension writes no internal operation logs.
    }

    /**
     * Rejects a warehouse stored in a format this jar does not know. Gson calls it after reading one from the
     * image or the edit log.
     */
    @Override
    public void gsonPostProcess() throws IOException {
        if (formatVersion < 1 || formatVersion > FORMAT_VERSION) {
            throw new IOException("Warehouse '" + getName() + "' is stored in format " + formatVersion
                    + ", and this warehouse extension jar reads formats 1 to " + FORMAT_VERSION
                    + ". Install the jar version that wrote it");
        }
    }

    @Override
    public String toString() {
        return "UserWarehouse{id=" + getId() + ", name=" + getName() + ", workerGroupId=" + workerGroupId
                + ", replicaNumber=" + replicaNumber + "}";
    }
}
