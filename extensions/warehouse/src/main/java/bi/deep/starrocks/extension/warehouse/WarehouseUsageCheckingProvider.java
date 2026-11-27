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

import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.CRAcquireContext;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.ComputeResourceProvider;

import java.util.List;
import java.util.Optional;

/** Checks the caller's USAGE before CACHE SELECT assigns resources to its internal sessions. */
final class WarehouseUsageCheckingProvider implements ComputeResourceProvider {
    private final FeEnvironment env;
    private final ComputeResourceProvider delegate;

    WarehouseUsageCheckingProvider(FeEnvironment env, ComputeResourceProvider delegate) {
        this.env = env;
        this.delegate = delegate;
    }

    @Override
    public List<ComputeResource> getComputeResources(Warehouse warehouse) {
        if (warehouse != null && env.shouldCheckUsage(warehouse.getId())) {
            env.requireUsage(warehouse.getName());
        }
        return delegate.getComputeResources(warehouse);
    }

    @Override
    public ComputeResource ofComputeResource(long warehouseId, long workerGroupId) {
        return delegate.ofComputeResource(warehouseId, workerGroupId);
    }

    @Override
    public Optional<ComputeResource> acquireComputeResource(Warehouse warehouse, CRAcquireContext acquireContext) {
        return delegate.acquireComputeResource(warehouse, acquireContext);
    }

    @Override
    public boolean isResourceAvailable(ComputeResource resource) {
        return delegate.isResourceAvailable(resource);
    }

    @Override
    public List<Long> getAllComputeNodeIds(ComputeResource resource) {
        return delegate.getAllComputeNodeIds(resource);
    }

    @Override
    public List<ComputeNode> getAliveComputeNodes(ComputeResource resource) {
        return delegate.getAliveComputeNodes(resource);
    }
}
