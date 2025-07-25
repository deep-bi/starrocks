// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.utframe;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.ComputeNode;
import com.starrocks.warehouse.DefaultWarehouse;
import com.starrocks.warehouse.Warehouse;
import com.starrocks.warehouse.cngroup.ComputeResource;
import com.starrocks.warehouse.cngroup.ComputeResourceProvider;
import com.starrocks.warehouse.cngroup.WarehouseComputeResourceProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MockedWarehouseManager is used to mock WarehouseManager for unit test.
 */
public class MockedWarehouseManager extends WarehouseManager {
    private final Map<Long, List<Long>> warehouseIdToComputeNodeIds = new HashMap<>();
    private final List<Long> computeNodeIdSetAssignedToTablet = new ArrayList<>();
    private final Set<ComputeNode> computeNodeSetAssignedToTablet = new HashSet<>();
    private final List<ComputeNode> aliveComputeNodes = new ArrayList<>();

    private Long computeNodeId = 1000L;
    private boolean throwUnknownWarehouseException = false;

    public MockedWarehouseManager() {
        this(new WarehouseComputeResourceProvider());
    }

    public MockedWarehouseManager(ComputeResourceProvider computeResourceProvider) {
        super(computeResourceProvider, new ArrayList<>());
        warehouseIdToComputeNodeIds.put(DEFAULT_WAREHOUSE_ID, List.of(1000L));
        computeNodeIdSetAssignedToTablet.addAll(Lists.newArrayList(1000L));
        ComputeNode computeNode = new ComputeNode(1000L, "127.0.0.1", 9030);
        computeNode.setAlive(true);
        computeNodeSetAssignedToTablet.addAll(Sets.newHashSet(computeNode));
    }
    @Override
    public Warehouse getWarehouse(String warehouseName) {
        Warehouse warehouse = nameToWh.get(warehouseName);
        if (warehouse != null) {
            return warehouse;
        }
        return new DefaultWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_ID,
                WarehouseManager.DEFAULT_WAREHOUSE_NAME);
    }

    @Override
    public boolean warehouseExists(long warehouseId) {
        return true;
    }

    @Override
    public Warehouse getWarehouse(long warehouseId) {
        Warehouse warehouse = idToWh.get(warehouseId);
        if (warehouse != null) {
            return warehouse;
        }
        return new DefaultWarehouse(WarehouseManager.DEFAULT_WAREHOUSE_ID,
                WarehouseManager.DEFAULT_WAREHOUSE_NAME);
    }

    @Override
    public List<Long> getAllComputeNodeIds(ComputeResource computeResource) {
        if (throwUnknownWarehouseException) {
            throw ErrorReportException.report(ErrorCode.ERR_UNKNOWN_WAREHOUSE, String.format("id: %d", 1L));
        }
        if (warehouseIdToComputeNodeIds.containsKey(computeResource.getWarehouseId())) {
            return warehouseIdToComputeNodeIds.get(computeResource.getWarehouseId());
        }
        return warehouseIdToComputeNodeIds.get(DEFAULT_WAREHOUSE_ID);
    }

    public void setAllComputeNodeIds(List<Long> computeNodeIds) {
        if (computeNodeIds == null) {
            warehouseIdToComputeNodeIds.remove(DEFAULT_WAREHOUSE_ID);
            return;
        }
        warehouseIdToComputeNodeIds.put(DEFAULT_WAREHOUSE_ID, computeNodeIds);
    }

    public void setComputeNodeId(Long computeNodeId) {
        this.computeNodeId = computeNodeId;
    }

    @Override
    public Long getComputeNodeId(ComputeResource computeResource, long tabletId) {
        return computeNodeId;
    }

    @Override
    public Long getAliveComputeNodeId(ComputeResource computeResource, long tabletId) {
        return computeNodeId;
    }

    @Override
    public List<Long> getAllComputeNodeIdsAssignToTablet(ComputeResource computeResource, long tabletId) {
        return computeNodeIdSetAssignedToTablet;
    }

    public void setComputeNodeIdsAssignToTablet(Set<Long> computeNodeIds) {
        computeNodeIdSetAssignedToTablet.addAll(computeNodeIds);
    }


    @Override
    public ComputeNode getComputeNodeAssignedToTablet(ComputeResource computeResource, long tabletId) {
        if (computeNodeSetAssignedToTablet.isEmpty()) {
            return null;
        } else {
            return computeNodeSetAssignedToTablet.iterator().next();
        }
    }

    public void setComputeNodesAssignedToTablet(Set<ComputeNode> computeNodeSet) {
        computeNodeSetAssignedToTablet.clear();
        if (computeNodeSet != null) {
            computeNodeSetAssignedToTablet.addAll(computeNodeSet);
        }
    }

    public void setThrowUnknownWarehouseException() {
        this.throwUnknownWarehouseException = true;
    }

    @Override
    public List<ComputeNode> getAliveComputeNodes(ComputeResource computeResource) {
        if (getAllComputeNodeIds(computeResource).isEmpty())  {
            return Lists.newArrayList();
        }
        if (!aliveComputeNodes.isEmpty()) {
            return aliveComputeNodes;
        }
        return new ArrayList<>(computeNodeSetAssignedToTablet);
    }

    public void setAliveComputeNodes(List<ComputeNode> computeNodes) {
        aliveComputeNodes.clear();
        if (computeNodes != null) {
            aliveComputeNodes.addAll(computeNodes);
        }
    }
}
