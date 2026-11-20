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

import com.staros.client.StarClient;
import com.staros.proto.ReplicationType;
import com.staros.proto.WarmupLevel;
import com.staros.proto.WorkerGroupDetailInfo;

import java.util.Collections;
import java.util.List;

/**
 * Talks to StarMgr directly, past the FE: reads the replica number of a worker group, or changes it.
 *
 * <pre>
 *   StarMgr &lt;host:port&gt; get &lt;worker group id&gt;
 *   StarMgr &lt;host:port&gt; set &lt;worker group id&gt; &lt;replica number&gt;
 * </pre>
 *
 * Both print the replica number StarMgr holds afterwards, or a line that starts with ERROR. The suites use
 * {@code set} to leave StarMgr with a number the FE never stored, which is what an FE that dies in the middle of
 * ALTER WAREHOUSE leaves behind.
 */
public class StarMgr {
    public static void main(String[] args) {
        int status = 0;
        try {
            long groupId = Long.parseLong(args[2]);
            StarClient client = new StarClient();
            client.connectServer(args[0]);
            String serviceId = client.getServiceInfoByName("starrocks").getServiceId();
            if ("set".equals(args[1])) {
                client.updateWorkerGroup(serviceId, groupId, null, null, Integer.parseInt(args[3]),
                        ReplicationType.NO_SET, WarmupLevel.WARMUP_NOT_SET);
            }
            List<WorkerGroupDetailInfo> groups = client.listWorkerGroup(serviceId, Collections.singletonList(groupId), false);
            System.out.println(groups.isEmpty() ? "ERROR no such worker group" : String.valueOf(groups.get(0).getReplicaNumber()));
        } catch (Exception e) {
            System.out.println("ERROR " + e);
            status = 1;
        }
        // The client's network threads would keep the JVM alive.
        System.exit(status);
    }
}
