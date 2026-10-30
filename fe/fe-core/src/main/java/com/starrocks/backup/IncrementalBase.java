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
import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Table;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// What an incremental backup keeps of its base snapshot: read once at submit, from the base's __info and
// __meta, and compared with the catalog when the snapshot tasks are created.
public class IncrementalBase {
    @SerializedName(value = "label")
    private String label;
    @SerializedName(value = "dbId")
    private long dbId;
    @SerializedName(value = "formatVersion")
    private int formatVersion;
    // table id -> index meta id -> schema identity
    @SerializedName(value = "indexIdentities")
    private Map<Long, Map<Long, IndexIdentity>> indexIdentities = Maps.newHashMap();
    // physical partition id -> visible version and time
    @SerializedName(value = "partitionVersions")
    private Map<Long, PartitionVersion> partitionVersions = Maps.newHashMap();
    @SerializedName(value = "manifests")
    private Map<Long, List<ManifestEntry>> manifests = Maps.newHashMap();

    public static IncrementalBase of(BackupJobInfo info, BackupMeta meta) {
        IncrementalBase base = new IncrementalBase();
        base.label = info.name;
        base.dbId = info.dbId;
        base.formatVersion = info.snapshotFormatVersion;
        for (Table table : meta.getTables().values()) {
            if (!table.isOlapTableOrMaterializedView()) {
                continue;
            }
            OlapTable olapTable = (OlapTable) table;
            base.indexIdentities.put(olapTable.getId(), indexIdentitiesOf(olapTable));
            for (Partition partition : olapTable.getPartitions()) {
                for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                    base.partitionVersions.put(physicalPartition.getId(), new PartitionVersion(physicalPartition));
                }
            }
        }
        base.manifests.putAll(info.getManifests());
        return base;
    }

    public String getLabel() {
        return label;
    }

    // A base from another cluster (or from before a DR restore) has other ids, and a legacy base has no
    // manifests: nothing of either can be reused.
    public boolean isReusableBy(long currentDbId) {
        return dbId == currentDbId && formatVersion != BackupJobInfo.SNAPSHOT_FORMAT_VERSION_LEGACY;
    }

    public boolean isUnchanged(OlapTable table, PhysicalPartition partition) {
        PartitionVersion version = partitionVersions.get(partition.getId());
        return version != null && version.equals(new PartitionVersion(partition))
                && indexIdentitiesOf(table).equals(indexIdentities.get(table.getId()));
    }

    // null when the base has no such tablet
    public List<ManifestEntry> getManifest(long tabletId) {
        List<ManifestEntry> manifest = manifests.get(tabletId);
        return manifest == null || manifest.isEmpty() ? null : manifest;
    }

    private static Map<Long, IndexIdentity> indexIdentitiesOf(OlapTable table) {
        Map<Long, IndexIdentity> identities = Maps.newHashMap();
        for (Map.Entry<Long, MaterializedIndexMeta> entry : table.getIndexMetaIdToMeta().entrySet()) {
            identities.put(entry.getKey(), new IndexIdentity(entry.getValue()));
        }
        return identities;
    }

    // Fast schema evolution keeps schemaHash but changes schemaId and schemaVersion, so all three count.
    private static class IndexIdentity {
        @SerializedName(value = "schemaId")
        private long schemaId;
        @SerializedName(value = "schemaVersion")
        private int schemaVersion;
        @SerializedName(value = "schemaHash")
        private int schemaHash;

        IndexIdentity(MaterializedIndexMeta meta) {
            schemaId = meta.getSchemaId();
            schemaVersion = meta.getSchemaVersion();
            schemaHash = meta.getSchemaHash();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IndexIdentity)) {
                return false;
            }
            IndexIdentity that = (IndexIdentity) o;
            return schemaId == that.schemaId && schemaVersion == that.schemaVersion && schemaHash == that.schemaHash;
        }

        @Override
        public int hashCode() {
            return Objects.hash(schemaId, schemaVersion, schemaHash);
        }
    }

    // The version time is part of the identity: an in-place restore can bring a partition back to a version
    // number it had before, with other data, but always with a new time.
    private static class PartitionVersion {
        @SerializedName(value = "version")
        private long version;
        @SerializedName(value = "versionTime")
        private long versionTime;

        PartitionVersion(PhysicalPartition partition) {
            version = partition.getVisibleVersion();
            versionTime = partition.getVisibleVersionTime();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PartitionVersion)) {
                return false;
            }
            PartitionVersion that = (PartitionVersion) o;
            return version == that.version && versionTime == that.versionTime;
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, versionTime);
        }
    }
}
