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

/**
 * Keeps StarMgr's worker groups in line with the stored warehouses.
 *
 * <p>{@code ALTER WAREHOUSE} changes the replica number in StarMgr first and writes the edit log second. An FE
 * that dies between the two leaves StarMgr with the new number and the FE with the old one, and nothing in the
 * FE would ever notice. The edit log is what the statement committed, so the stored number is the right one.
 * Once a minute the leader compares the two for every warehouse and sets StarMgr back where they differ.
 *
 * <p>Only the leader does this, and only once it has replayed the whole edit log. A follower can be a moment
 * behind: it could see the old stored number while the leader has already put the new one into StarMgr, and
 * would then undo a statement that is about to commit.
 */
final class StarMgrSync {
    static final String NAME = "warehouse-extension-starmgr-sync";
    static final long CHECK_INTERVAL_MS = 60_000L;

    private StarMgrSync() {
    }

    /**
     * Starts the periodic check. The FE environment runs one per FE process, so calling this again does nothing.
     *
     * @return true when this call started it
     */
    static boolean install(FeEnvironment env) {
        return env.runPeriodically(NAME, CHECK_INTERVAL_MS, () -> run(env));
    }

    /**
     * One round.
     *
     * @return the number of worker groups it corrected; 0 on an FE that is not the leader
     */
    static int run(FeEnvironment env) {
        if (!env.isActiveLeader()) {
            return 0;
        }
        // Looked up on every round: the task outlives the manager that started it, and a checkpoint's copy of
        // the manager must never be the one that writes to StarMgr.
        MultiWarehouseManager manager = env.servingManager();
        return manager == null ? 0 : manager.alignWorkerGroups();
    }
}
