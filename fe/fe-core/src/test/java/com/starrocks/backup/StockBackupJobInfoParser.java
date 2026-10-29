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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.backup.BackupJobInfo.BackupIndexInfo;
import com.starrocks.backup.BackupJobInfo.BackupPartitionInfo;
import com.starrocks.backup.BackupJobInfo.BackupPhysicalPartitionInfo;
import com.starrocks.backup.BackupJobInfo.BackupTableInfo;
import com.starrocks.backup.BackupJobInfo.BackupTabletInfo;
import com.starrocks.common.FeConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

// BackupJobInfo.genFromJson and sortTabletIds exactly as in 4.1.6 (commit b5d9da70a33), before incremental
// backup, so tests can check that an FE without the feature still reads the __info the new FE writes.
public class StockBackupJobInfoParser {
    private static final Logger LOG = LogManager.getLogger(StockBackupJobInfoParser.class);

    public static BackupJobInfo parse(String json) {
        BackupJobInfo jobInfo = new BackupJobInfo();
        genFromJson(json, jobInfo);
        return jobInfo;
    }

    private static void genFromJson(String json, BackupJobInfo jobInfo) {
        /* parse the json string:
         * {
         *   "backup_time": 1522231864000,
         *   "name": "snapshot1",
         *   "database": "db1"
         *   "id": 10000
         *   "backup_result": "succeed",
         *   "meta_version" : 40 // for community meta version, optional
         *   "starrocks_meta_version" : 1  //for starrocks meta version, optional
         *   "backup_objects": {
         *       "table1": {
         *           "partitions": {
         *               "partition2": {
         *                   "indexes": {
         *                       "rollup1": {
         *                           "id": 10009,
         *                           "schema_hash": 3473401,
         *                           "tablets": {
         *                               "10008": ["__10029_seg1.dat", "__10029_seg2.dat"],
         *                               "10007": ["__10029_seg1.dat", "__10029_seg2.dat"]
         *                           },
         *                           "tablets_order": ["10007", "10008"]
         *                       },
         *                       "table1": {
         *                           "id": 10008,
         *                           "schema_hash": 9845021,
         *                           "tablets": {
         *                               "10004": ["__10027_seg1.dat", "__10027_seg2.dat"],
         *                               "10005": ["__10028_seg1.dat", "__10028_seg2.dat"]
         *                           },
         *                           "tablets_order": ["10004, "10005"]
         *                       }
         *                   },
         *                   "id": 10007
         *                   "version": 10
         *               },
         *           },
         *           "id": 10001
         *           "autoIncrementId": 10000
         *       }
         *   }
         * }
         */
        JSONObject root = new JSONObject(json);
        jobInfo.name = (String) root.get("name");
        jobInfo.dbName = (String) root.get("database");
        jobInfo.dbId = root.getLong("id");
        jobInfo.backupTime = root.getLong("backup_time");

        try {
            jobInfo.metaVersion = root.getInt("meta_version");
        } catch (JSONException e) {
            // meta_version does not exist
            jobInfo.metaVersion = FeConstants.META_VERSION;
        }
        try {
            jobInfo.starrocksMetaVersion = root.getInt("starrocks_meta_version");
        } catch (JSONException e) {
            // starrocks_meta_version does not exist
            jobInfo.starrocksMetaVersion = FeConstants.STARROCKS_META_VERSION;
        }

        JSONObject backupObjs = root.getJSONObject("backup_objects");
        String[] tblNames = JSONObject.getNames(backupObjs);
        if (tblNames == null) {
            // means that pure snapshot for functions
            String result = root.getString("backup_result");
            if (result.equals("succeed")) {
                jobInfo.success = true;
            } else {
                jobInfo.success = false;
            }
            return;
        }
        for (String tblName : tblNames) {
            BackupTableInfo tblInfo = new BackupTableInfo();
            tblInfo.name = tblName;
            JSONObject tbl = backupObjs.getJSONObject(tblName);
            tblInfo.id = tbl.getLong("id");
            try {
                tblInfo.autoIncrementId = tbl.getLong("autoIncrementId");
            } catch (Exception e) {
                tblInfo.autoIncrementId = null;
            }
            JSONObject parts = tbl.getJSONObject("partitions");
            String[] partsNames = JSONObject.getNames(parts);
            if (partsNames == null) {
                // skip logical view
                jobInfo.tables.put(tblName, tblInfo);
                continue;
            }
            for (String partName : partsNames) {
                BackupPartitionInfo partInfo = new BackupPartitionInfo();
                partInfo.name = partName;
                JSONObject part = parts.getJSONObject(partName);
                partInfo.id = part.getLong("id");
                partInfo.version = part.getLong("version");
                JSONObject indexes = part.getJSONObject("indexes");
                String[] indexNames = JSONObject.getNames(indexes);
                if (indexNames != null) {
                    for (String idxName : indexNames) {
                        BackupIndexInfo indexInfo = new BackupIndexInfo();
                        indexInfo.name = idxName;
                        JSONObject idx = indexes.getJSONObject(idxName);
                        indexInfo.id = idx.getLong("id");
                        indexInfo.schemaHash = idx.getInt("schema_hash");
                        JSONObject tablets = idx.getJSONObject("tablets");
                        String[] tabletIds = JSONObject.getNames(tablets);

                        JSONArray tabletsOrder = null;
                        if (idx.has("tablets_order")) {
                            tabletsOrder = idx.getJSONArray("tablets_order");
                        }
                        String[] orderedTabletIds = sortTabletIds(tabletIds, tabletsOrder);
                        Preconditions.checkState(tabletIds.length == orderedTabletIds.length);

                        for (String tabletId : orderedTabletIds) {
                            BackupTabletInfo tabletInfo = new BackupTabletInfo();
                            tabletInfo.id = Long.valueOf(tabletId);
                            indexInfo.tablets.add(tabletInfo);
                        }
                        partInfo.indexes.put(indexInfo.name, indexInfo);
                    }
                }
                JSONObject subPartitions = null;
                try {
                    subPartitions = part.getJSONObject("subPartitions");
                    String[] subPartitionIds = JSONObject.getNames(subPartitions);
                    if (subPartitionIds != null) {
                        for (String subPartitionId : subPartitionIds) {
                            BackupPhysicalPartitionInfo subPartInfo = new BackupPhysicalPartitionInfo();
                            JSONObject subPart = subPartitions.getJSONObject(subPartitionId);
                            subPartInfo.id = subPart.getLong("id");
                            subPartInfo.version = subPart.getLong("version");
                            JSONObject subIndexes = subPart.getJSONObject("indexes");
                            String[] idxNames = JSONObject.getNames(subIndexes);
                            for (String idxName : idxNames) {
                                BackupIndexInfo indexInfo = new BackupIndexInfo();
                                indexInfo.name = idxName;
                                JSONObject idx = subIndexes.getJSONObject(idxName);
                                indexInfo.id = idx.getLong("id");
                                indexInfo.schemaHash = idx.getInt("schema_hash");
                                JSONObject tablets = idx.getJSONObject("tablets");
                                String[] tabletIds = JSONObject.getNames(tablets);

                                JSONArray tabletsOrder = null;
                                if (idx.has("tablets_order")) {
                                    tabletsOrder = idx.getJSONArray("tablets_order");
                                }
                                String[] orderedTabletIds = sortTabletIds(tabletIds, tabletsOrder);
                                Preconditions.checkState(tabletIds.length == orderedTabletIds.length);

                                for (String tabletId : orderedTabletIds) {
                                    BackupTabletInfo tabletInfo = new BackupTabletInfo();
                                    tabletInfo.id = Long.valueOf(tabletId);
                                    indexInfo.tablets.add(tabletInfo);
                                }
                                subPartInfo.indexes.put(indexInfo.name, indexInfo);
                            }
                            partInfo.subPartitions.put(subPartInfo.id, subPartInfo);
                        }
                    }
                } catch (JSONException e) {
                    // subPartitions does not exist
                }

                tblInfo.partitions.put(partName, partInfo);
            }
            jobInfo.tables.put(tblName, tblInfo);
        }

        LOG.debug("BackupJobInfo: {}", jobInfo);

        String result = root.getString("backup_result");
        if (result.equals("succeed")) {
            jobInfo.success = true;
        } else {
            jobInfo.success = false;
        }
    }

    private static String[] sortTabletIds(String[] tabletIds, JSONArray tabletsOrder) {
        if (tabletsOrder == null || tabletsOrder.toList().isEmpty()) {
            // in previous version, we are not saving tablets order(which was a BUG),
            // so we have to sort the tabletIds to restore the original order of tablets.
            List<String> tmpList = Lists.newArrayList(tabletIds);
            tmpList.sort((o1, o2) -> Long.valueOf(o1).compareTo(Long.valueOf(o2)));
            return tmpList.toArray(new String[0]);
        } else {
            // StarRocks uses string to deserialize tablets_order and apache doris uses long to deserialize.
            // for compatibility with apache doris.
            return tabletsOrder.toList().stream().map(Object::toString).toArray(String[]::new);
        }
    }
}
