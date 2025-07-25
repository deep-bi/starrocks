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

package com.starrocks.analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedView;
import com.starrocks.catalog.MvId;
import com.starrocks.catalog.MvUpdateInfo;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Tablet;
import com.starrocks.clone.DynamicPartitionScheduler;
import com.starrocks.qe.StmtExecutor;
import com.starrocks.schema.MTable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.AlterMaterializedViewStmt;
import com.starrocks.sql.ast.DmlStmt;
import com.starrocks.sql.ast.DropPartitionClause;
import com.starrocks.sql.ast.InsertStmt;
import com.starrocks.sql.ast.RefreshMaterializedViewStatement;
import com.starrocks.sql.ast.TruncateTableStmt;
import com.starrocks.sql.common.PListCell;
import com.starrocks.sql.optimizer.rule.transformation.materialization.MVTestBase;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RefreshMaterializedViewTest extends MVTestBase {
    // 1hour: set it to 1 hour to avoid FE's async update too late.
    private static final long MV_STALENESS = 60 * 60;

    @BeforeAll
    public static void beforeClass() throws Exception {
        MVTestBase.beforeClass();
        starRocksAssert.useDatabase("test")
                .withTable("CREATE TABLE test.tbl_with_mv\n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int sum\n" +
                        ")\n" +
                        "PARTITION BY RANGE(k1)\n" +
                        "(\n" +
                        "    PARTITION p1 values [('2022-02-01'),('2022-02-16')),\n" +
                        "    PARTITION p2 values [('2022-02-16'),('2022-03-01'))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                // table whose partitions have only single values
                .withTable("CREATE TABLE s2 (\n" +
                        "      id BIGINT,\n" +
                        "      age SMALLINT,\n" +
                        "      dt VARCHAR(10),\n" +
                        "      province VARCHAR(64) not null\n" +
                        ")\n" +
                        "DUPLICATE KEY(id)\n" +
                        "PARTITION BY LIST (dt) (\n" +
                        "     PARTITION p1 VALUES IN (\"20240101\") ,\n" +
                        "     PARTITION p2 VALUES IN (\"20240102\") ,\n" +
                        "     PARTITION p3 VALUES IN (\"20240103\") \n" +
                        ")\n" +
                        "DISTRIBUTED BY RANDOM\n")
                .withMaterializedView("create materialized view mv_to_refresh\n" +
                        "distributed by hash(k2) buckets 3\n" +
                        "refresh manual\n" +
                        "as select k2, sum(v1) as total from tbl_with_mv group by k2;")
                .withMaterializedView("create materialized view mv2_to_refresh\n" +
                        "PARTITION BY k1\n" +
                        "distributed by hash(k2) buckets 3\n" +
                        "refresh manual\n" +
                        "as select k1, k2, v1  from tbl_with_mv;");
    }

    @Test
    public void testCreateMVProperties1() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");

        withRefreshedMV("CREATE MATERIALIZED VIEW mv1 \n" +
                "PARTITION BY date_trunc('day', k1)\n"
                + "PROPERTIES (\n"
                + "\"excluded_refresh_tables\" = \"t2\"\n"
                + ")\n"
                + "DISTRIBUTED BY RANDOM\n" +
                "REFRESH ASYNC\n" +
                "AS \n" +
                "select k1 from (SELECT * FROM t1 UNION ALL SELECT * FROM t2) t group by k1\n", () -> {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
            MaterializedView mv =
                    (MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv1");
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t1"));
            Assertions.assertFalse(mv.shouldRefreshTable("test", "t2"));

            // cleanup
            starRocksAssert.dropTable("t1");
            starRocksAssert.dropTable("t2");
            starRocksAssert.dropMaterializedView("mv1");
        });
    }

    @Test
    public void testCreateMVProperties2() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
        withRefreshedMV("CREATE MATERIALIZED VIEW mv1 \n" +
                "PARTITION BY date_trunc('day', k1)\n"
                + "DISTRIBUTED BY RANDOM\n" +
                "REFRESH ASYNC\n" +
                "AS \n" +
                "select k1 from (SELECT * FROM t1 UNION ALL SELECT * FROM t2) t group by k1\n", () -> {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
            MaterializedView mv =
                    (MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv1");
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t1"));
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t2"));

            String alterSql = "ALTER MATERIALIZED VIEW mv1 SET ('excluded_refresh_tables' = 't2')";
            AlterMaterializedViewStmt stmt =
                    (AlterMaterializedViewStmt) UtFrameUtils.parseStmtWithNewParser(alterSql, connectContext);
            GlobalStateMgr.getCurrentState().getLocalMetastore().alterMaterializedView(stmt);
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t1"));
            Assertions.assertFalse(mv.shouldRefreshTable("test", "t2"));

            // cleanup
            starRocksAssert.dropTable("t1");
            starRocksAssert.dropTable("t2");
            starRocksAssert.dropMaterializedView("mv1");
        });
    }

    @Test
    public void testCreateMVProperties3() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
        starRocksAssert.createDatabaseIfNotExists("test2");
        withRefreshedMV("CREATE MATERIALIZED VIEW test2.mv1 \n" +
                "PARTITION BY date_trunc('day', k1)\n"
                + "DISTRIBUTED BY RANDOM\n" +
                "REFRESH ASYNC\n" +
                "AS \n" +
                "select k1 from (SELECT * FROM test.t1 UNION ALL SELECT * FROM test.t2) t group by k1\n", () -> {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test2");
            MaterializedView mv =
                    (MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv1");
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t1"));
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t2"));

            String alterSql = "ALTER MATERIALIZED VIEW test2.mv1 SET ('excluded_refresh_tables' = 'test.t2')";
            AlterMaterializedViewStmt stmt =
                    (AlterMaterializedViewStmt) UtFrameUtils.parseStmtWithNewParser(alterSql, connectContext);
            GlobalStateMgr.getCurrentState().getLocalMetastore().alterMaterializedView(stmt);
            Assertions.assertTrue(mv.shouldRefreshTable("test", "t1"));
            Assertions.assertFalse(mv.shouldRefreshTable("test", "t2"));
            Assertions.assertTrue(mv.shouldRefreshTable("test2", "t2"));

            // cleanup
            starRocksAssert.dropTable("t1");
            starRocksAssert.dropTable("t2");
            starRocksAssert.dropMaterializedView("mv1");
        });
    }


    @Test
    public void testNormal() throws Exception {
        String refreshMvSql = "refresh materialized view test.mv_to_refresh";
        RefreshMaterializedViewStatement alterMvStmt =
                (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(refreshMvSql, connectContext);
        String dbName = alterMvStmt.getMvName().getDb();
        String mvName = alterMvStmt.getMvName().getTbl();
        Assertions.assertEquals("test", dbName);
        Assertions.assertEquals("mv_to_refresh", mvName);

        String sql = "REFRESH MATERIALIZED VIEW test.mv2_to_refresh PARTITION START('2022-02-03') END ('2022-02-25') FORCE;";
        RefreshMaterializedViewStatement statement =
                (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        Assertions.assertTrue(statement.isForceRefresh());
        Assertions.assertEquals("2022-02-03", statement.getPartitionRangeDesc().getPartitionStart());
        Assertions.assertEquals("2022-02-25", statement.getPartitionRangeDesc().getPartitionEnd());

        try {
            sql = "REFRESH MATERIALIZED VIEW test.mv_to_refresh PARTITION START('2022-02-03') END ('2022-02-25') FORCE;";
            statement = (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        } catch (Exception e) {
            Assertions.assertEquals("Getting analyzing error from line 1, column 26 to line 1, column 31. " +
                    "Detail message: Not support refresh by partition for single partition mv.", e.getMessage());
        }

        try {
            sql = "REFRESH MATERIALIZED VIEW test.mv2_to_refresh PARTITION START('2022-02-03') END ('2020-02-25') FORCE;";
            statement = (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        } catch (Exception e) {
            Assertions.assertEquals("Getting analyzing error from line 1, column 56 to line 1, column 93. " +
                    "Detail message: Batch build partition start date should less than end date.", e.getMessage());
        }

        try {
            sql = "REFRESH MATERIALIZED VIEW test.mv2_to_refresh PARTITION START('dhdfghg') END ('2020-02-25') FORCE;";
            statement = (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
        } catch (Exception e) {
            Assertions.assertEquals("Getting analyzing error from line 1, column 56 to line 1, column 90. " +
                            "Detail message: Batch build partition EVERY is date type but START or END does not type match.",
                    e.getMessage());
        }
    }

    @Test
    public void testRefreshExecution() throws Exception {
        executeInsertSql(connectContext, "insert into tbl_with_mv values(\"2022-02-20\", 1, 10)");
        refreshMaterializedView("test", "mv_to_refresh");
        MaterializedView mv1 = getMv("test", "mv_to_refresh");
        Set<String> partitionsToRefresh1 = getPartitionNamesToRefreshForMv(mv1);
        Assertions.assertTrue(partitionsToRefresh1.isEmpty());
        refreshMaterializedView("test", "mv2_to_refresh");
        MaterializedView mv2 = getMv("test", "mv2_to_refresh");
        Set<String> partitionsToRefresh2 = getPartitionNamesToRefreshForMv(mv2);
        Assertions.assertTrue(partitionsToRefresh2.isEmpty());

        executeInsertSql(connectContext, "insert into tbl_with_mv partition(p2) values(\"2022-02-20\", 2, 10)");
        OlapTable table = (OlapTable) getTable("test", "tbl_with_mv");
        Partition p1 = table.getPartition("p1");
        Partition p2 = table.getPartition("p2");
        if (p2.getDefaultPhysicalPartition().getVisibleVersion() == 3) {
            MvUpdateInfo mvUpdateInfo = getMvUpdateInfo(mv1);
            Assertions.assertTrue(mvUpdateInfo.getMvToRefreshType() == MvUpdateInfo.MvToRefreshType.FULL);
            Assertions.assertTrue(!mvUpdateInfo.isValidRewrite());
            partitionsToRefresh1 = getPartitionNamesToRefreshForMv(mv1);
            Assertions.assertTrue(partitionsToRefresh1.isEmpty());

            partitionsToRefresh2 = getPartitionNamesToRefreshForMv(mv2);
            Assertions.assertTrue(partitionsToRefresh2.contains("p2"));
        } else {
            // publish version is async, so version update may be late
            // for debug
            System.out.println("p1 visible version:" + p1.getDefaultPhysicalPartition().getVisibleVersion());
            System.out.println("p2 visible version:" + p2.getDefaultPhysicalPartition().getVisibleVersion());
            System.out.println("mv1 refresh context" + mv1.getRefreshScheme().getAsyncRefreshContext());
            System.out.println("mv2 refresh context" + mv2.getRefreshScheme().getAsyncRefreshContext());
        }
    }

    @Test
    public void testMaxMVRewriteStaleness1() {

        starRocksAssert.withTable(new MTable("tbl_staleness1", "k2",
                        List.of(
                                "k1 date",
                                "k2 int",
                                "v1 int"
                        ),
                        "k1",
                        List.of(
                                "PARTITION p1 values [('2022-02-01'),('2022-02-16'))",
                                "PARTITION p2 values [('2022-02-16'),('2022-03-01'))"
                        )
                ),
                () -> {
                    starRocksAssert.withMaterializedView("create materialized view mv_with_mv_rewrite_staleness\n" +
                            "PARTITION BY k1\n" +
                            "distributed by hash(k2) buckets 3\n" +
                            "PROPERTIES (\n" +
                            "\"replication_num\" = \"1\"" +
                            ")" +
                            "refresh manual\n" +
                            "as select k1, k2, v1  from tbl_staleness1;");

                    // refresh partitions are not empty if base table is updated.
                    executeInsertSql(connectContext, "insert into tbl_staleness1 partition(p2) " +
                            "values(\"2022-02-20\", 1, 10)");
                    {
                        refreshMaterializedView("test", "mv_with_mv_rewrite_staleness");
                    }

                    // alter mv_rewrite_staleness
                    {
                        String alterMvSql = String.format("alter materialized view mv_with_mv_rewrite_staleness " +
                                "set (\"mv_rewrite_staleness_second\" = \"%s\")", MV_STALENESS);
                        AlterMaterializedViewStmt stmt =
                                (AlterMaterializedViewStmt) UtFrameUtils.parseStmtWithNewParser(alterMvSql, connectContext);
                        GlobalStateMgr.getCurrentState().getLocalMetastore().alterMaterializedView(stmt);
                    }
                    // no refresh partitions if mv_rewrite_staleness is set.
                    executeInsertSql(connectContext, "insert into tbl_staleness1 values(\"2022-02-20\", 1, 10)");
                    {
                        MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness");
                        checkToRefreshPartitionsEmpty(mv1);
                    }

                    // no refresh partitions if there is no new data.
                    {
                        refreshMaterializedView("test", "mv_with_mv_rewrite_staleness");
                        MaterializedView mv2 = getMv("test", "mv_with_mv_rewrite_staleness");
                        checkToRefreshPartitionsEmpty(mv2);
                    }
                    // no refresh partitions if there is new data & no refresh but is set `mv_rewrite_staleness`.
                    {
                        executeInsertSql(connectContext, "insert into tbl_staleness1 values(\"2022-02-22\", 1, 10)");
                        MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness");
                        checkToRefreshPartitionsEmpty(mv1);
                    }
                    starRocksAssert.dropMaterializedView("mv_with_mv_rewrite_staleness");
                }
        );
    }

    private void checkToRefreshPartitionsEmpty(MaterializedView mv) {
        Set<String> partitionsToRefresh = getPartitionNamesToRefreshForMv(mv);
        Assertions.assertTrue(partitionsToRefresh.isEmpty());
    }

    @Test
    public void testMaxMVRewriteStaleness2() {
        starRocksAssert.withTable(new MTable("tbl_staleness2", "k2",
                        List.of(
                                "k1 date",
                                "k2 int",
                                "v1 int"
                        ),
                        "k1",
                        List.of(
                                "PARTITION p1 values [('2022-02-01'),('2022-02-16'))",
                                "PARTITION p2 values [('2022-02-16'),('2022-03-01'))"
                        )
                ),
                () -> {
                    starRocksAssert.withMaterializedView("create materialized view mv_with_mv_rewrite_staleness2 \n" +
                            "PARTITION BY k1\n" +
                            "distributed by hash(k2) buckets 3\n" +
                            "PROPERTIES (\n" +
                            "\"replication_num\" = \"1\"," +
                            "\"mv_rewrite_staleness_second\" = \"" + MV_STALENESS + "\"" +
                            ")" +
                            "refresh manual\n" +
                            "as select k1, k2, v1  from tbl_staleness2;");

                    // refresh partitions are not empty if base table is updated.
                    {
                        executeInsertSql(connectContext,
                                "insert into tbl_staleness2 partition(p2) values(\"2022-02-20\", 1, 10)");
                        refreshMaterializedView("test", "mv_with_mv_rewrite_staleness2");

                        Table tbl1 = getTable("test", "tbl_staleness2");
                        Optional<Long> maxPartitionRefreshTimestamp =
                                tbl1.getPartitions().stream().map(
                                        p -> p.getDefaultPhysicalPartition().getVisibleVersionTime()).max(Long::compareTo);
                        Assertions.assertTrue(maxPartitionRefreshTimestamp.isPresent());

                        MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness2");
                        Assertions.assertTrue(mv1.maxBaseTableRefreshTimestamp().isPresent());
                        Assertions.assertEquals(mv1.maxBaseTableRefreshTimestamp().get(), maxPartitionRefreshTimestamp.get());

                        long mvRefreshTimeStamp = mv1.getLastRefreshTime();
                        Assertions.assertTrue(mvRefreshTimeStamp == maxPartitionRefreshTimestamp.get());
                        Assertions.assertTrue(mv1.isStalenessSatisfied());
                    }

                    // no refresh partitions if mv_rewrite_staleness is set.
                    {
                        executeInsertSql(connectContext, "insert into tbl_staleness2 values(\"2022-02-20\", 2, 10)");

                        Table tbl1 = getTable("test", "tbl_staleness2");
                        Optional<Long> maxPartitionRefreshTimestamp =
                                tbl1.getPartitions().stream().map(
                                        p -> p.getDefaultPhysicalPartition().getVisibleVersionTime()).max(Long::compareTo);
                        Assertions.assertTrue(maxPartitionRefreshTimestamp.isPresent());

                        MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness2");
                        Assertions.assertTrue(mv1.maxBaseTableRefreshTimestamp().isPresent());

                        long mvMaxBaseTableRefreshTimestamp = mv1.maxBaseTableRefreshTimestamp().get();
                        long tblMaxPartitionRefreshTimestamp = maxPartitionRefreshTimestamp.get();
                        Assertions.assertEquals(mvMaxBaseTableRefreshTimestamp, tblMaxPartitionRefreshTimestamp);

                        long mvRefreshTimeStamp = mv1.getLastRefreshTime();
                        Assertions.assertTrue(mvRefreshTimeStamp < tblMaxPartitionRefreshTimestamp);
                        Assertions.assertTrue((tblMaxPartitionRefreshTimestamp - mvRefreshTimeStamp) / 1000 < MV_STALENESS);
                        Assertions.assertTrue(mv1.isStalenessSatisfied());

                        Set<String> partitionsToRefresh = getPartitionNamesToRefreshForMv(mv1);
                        Assertions.assertTrue(partitionsToRefresh.isEmpty());
                    }
                    starRocksAssert.dropMaterializedView("mv_with_mv_rewrite_staleness2");
                }
        );
    }

    @Test
    public void testMaxMVRewriteStaleness3() {
        starRocksAssert.withTable(new MTable("tbl_staleness3", "k2",
                        List.of(
                                "k1 date",
                                "k2 int",
                                "v1 int"
                        ),
                        "k1",
                        List.of(
                                "PARTITION p1 values [('2022-02-01'),('2022-02-16'))",
                                "PARTITION p2 values [('2022-02-16'),('2022-03-01'))"
                        )
                ),
                () -> {
                    starRocksAssert.withMaterializedView("create materialized view mv_with_mv_rewrite_staleness21 \n" +
                            "PARTITION BY k1\n" +
                            "distributed by hash(k2) buckets 3\n" +
                            "PROPERTIES (\n" +
                            "\"replication_num\" = \"1\"," +
                            "\"mv_rewrite_staleness_second\" = \"" + MV_STALENESS + "\"" +
                            ")" +
                            "refresh manual\n" +
                            "as select k1, k2, v1  from tbl_staleness3;");
                    starRocksAssert.withMaterializedView("create materialized view mv_with_mv_rewrite_staleness22 \n" +
                            "PARTITION BY k1\n" +
                            "distributed by hash(k2) buckets 3\n" +
                            "PROPERTIES (\n" +
                            "\"replication_num\" = \"1\"," +
                            "\"mv_rewrite_staleness_second\" = \"" + MV_STALENESS + "\"" +
                            ")" +
                            "refresh manual\n" +
                            "as select k1, k2, count(1)  from mv_with_mv_rewrite_staleness21 group by k1, k2;");

                    {
                        executeInsertSql(connectContext,
                                "insert into tbl_staleness3 partition(p2) values(\"2022-02-20\", 1, 10)");
                        refreshMaterializedView("test", "mv_with_mv_rewrite_staleness21");
                        refreshMaterializedView("test", "mv_with_mv_rewrite_staleness22");
                        {
                            Table tbl1 = getTable("test", "tbl_staleness3");
                            Optional<Long> maxPartitionRefreshTimestamp =
                                    tbl1.getPartitions().stream().map(
                                            p -> p.getDefaultPhysicalPartition().getVisibleVersionTime()).max(Long::compareTo);
                            Assertions.assertTrue(maxPartitionRefreshTimestamp.isPresent());

                            MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness21");
                            Assertions.assertTrue(mv1.maxBaseTableRefreshTimestamp().isPresent());
                            Assertions.assertEquals(mv1.maxBaseTableRefreshTimestamp().get(), maxPartitionRefreshTimestamp.get());

                            long mvRefreshTimeStamp = mv1.getLastRefreshTime();
                            Assertions.assertTrue(mvRefreshTimeStamp == maxPartitionRefreshTimestamp.get());
                            Assertions.assertTrue(mv1.isStalenessSatisfied());
                        }

                        {
                            Table tbl1 = getTable("test", "mv_with_mv_rewrite_staleness21");
                            Optional<Long> maxPartitionRefreshTimestamp =
                                    tbl1.getPartitions().stream().map(
                                            p -> p.getDefaultPhysicalPartition().getVisibleVersionTime()).max(Long::compareTo);
                            Assertions.assertTrue(maxPartitionRefreshTimestamp.isPresent());

                            MaterializedView mv2 = getMv("test", "mv_with_mv_rewrite_staleness22");
                            Assertions.assertTrue(mv2.maxBaseTableRefreshTimestamp().isPresent());
                            Assertions.assertEquals(mv2.maxBaseTableRefreshTimestamp().get(), maxPartitionRefreshTimestamp.get());

                            long mvRefreshTimeStamp = mv2.getLastRefreshTime();
                            Assertions.assertTrue(mvRefreshTimeStamp == maxPartitionRefreshTimestamp.get());
                            Assertions.assertTrue(mv2.isStalenessSatisfied());
                        }
                    }

                    {
                        executeInsertSql(connectContext, "insert into tbl_staleness3 values(\"2022-02-20\", 2, 10)");
                        {
                            Table tbl1 = getTable("test", "tbl_staleness3");
                            Optional<Long> maxPartitionRefreshTimestamp =
                                    tbl1.getPartitions().stream().map(
                                            p -> p.getDefaultPhysicalPartition().getVisibleVersionTime()).max(Long::compareTo);
                            Assertions.assertTrue(maxPartitionRefreshTimestamp.isPresent());

                            MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness21");
                            Assertions.assertTrue(mv1.maxBaseTableRefreshTimestamp().isPresent());

                            long mvMaxBaseTableRefreshTimestamp = mv1.maxBaseTableRefreshTimestamp().get();
                            long tblMaxPartitionRefreshTimestamp = maxPartitionRefreshTimestamp.get();
                            Assertions.assertEquals(mvMaxBaseTableRefreshTimestamp, tblMaxPartitionRefreshTimestamp);

                            long mvRefreshTimeStamp = mv1.getLastRefreshTime();
                            Assertions.assertTrue(mvRefreshTimeStamp < tblMaxPartitionRefreshTimestamp);
                            Assertions.assertTrue((tblMaxPartitionRefreshTimestamp - mvRefreshTimeStamp) / 1000 < MV_STALENESS);
                            Assertions.assertTrue(mv1.isStalenessSatisfied());

                            Set<String> partitionsToRefresh = getPartitionNamesToRefreshForMv(mv1);
                            Assertions.assertTrue(partitionsToRefresh.isEmpty());
                        }
                        {
                            Table tbl1 = getTable("test", "mv_with_mv_rewrite_staleness21");
                            Optional<Long> maxPartitionRefreshTimestamp =
                                    tbl1.getPartitions().stream().map(
                                            p -> p.getDefaultPhysicalPartition().getVisibleVersionTime()).max(Long::compareTo);
                            Assertions.assertTrue(maxPartitionRefreshTimestamp.isPresent());

                            MaterializedView mv2 = getMv("test", "mv_with_mv_rewrite_staleness22");
                            Assertions.assertTrue(mv2.maxBaseTableRefreshTimestamp().isPresent());

                            long mvMaxBaseTableRefreshTimestamp = mv2.maxBaseTableRefreshTimestamp().get();
                            long tblMaxPartitionRefreshTimestamp = maxPartitionRefreshTimestamp.get();
                            Assertions.assertEquals(mvMaxBaseTableRefreshTimestamp, tblMaxPartitionRefreshTimestamp);

                            long mvRefreshTimeStamp = mv2.getLastRefreshTime();
                            Assertions.assertTrue(mvRefreshTimeStamp <= tblMaxPartitionRefreshTimestamp);
                            Assertions.assertTrue((tblMaxPartitionRefreshTimestamp - mvRefreshTimeStamp) / 1000 < MV_STALENESS);
                            Assertions.assertTrue(mv2.isStalenessSatisfied());

                            Set<String> partitionsToRefresh = getPartitionNamesToRefreshForMv(mv2);
                            Assertions.assertTrue(partitionsToRefresh.isEmpty());
                        }
                    }

                    {
                        executeInsertSql(connectContext, "insert into tbl_staleness3 values(\"2022-02-20\", 2, 10)");
                        {
                            // alter mv_rewrite_staleness
                            {
                                String alterMvSql = "alter materialized view mv_with_mv_rewrite_staleness21 " +
                                        "set (\"mv_rewrite_staleness_second\" = \"0\")";
                                AlterMaterializedViewStmt stmt =
                                        (AlterMaterializedViewStmt) UtFrameUtils.parseStmtWithNewParser(alterMvSql,
                                                connectContext);
                                GlobalStateMgr.getCurrentState().getLocalMetastore().alterMaterializedView(stmt);
                            }

                            MaterializedView mv1 = getMv("test", "mv_with_mv_rewrite_staleness21");
                            Assertions.assertFalse(mv1.isStalenessSatisfied());
                            Assertions.assertTrue(mv1.maxBaseTableRefreshTimestamp().isPresent());

                            MaterializedView mv2 = getMv("test", "mv_with_mv_rewrite_staleness22");
                            Assertions.assertFalse(mv1.isStalenessSatisfied());
                            Assertions.assertFalse(mv2.maxBaseTableRefreshTimestamp().isPresent());

                            Set<String> partitionsToRefresh = getPartitionNamesToRefreshForMv(mv2);
                            Assertions.assertFalse(partitionsToRefresh.isEmpty());
                        }
                    }
                    starRocksAssert.dropMaterializedView("mv_with_mv_rewrite_staleness21");
                    starRocksAssert.dropMaterializedView("mv_with_mv_rewrite_staleness22");
                }
        );
    }

    @Test
    public void testRefreshHourPartitionMv() throws Exception {
        new MockUp<StmtExecutor>() {
            @Mock
            public void handleDMLStmt(ExecPlan execPlan, DmlStmt stmt) throws Exception {
                if (stmt instanceof InsertStmt) {
                    InsertStmt insertStmt = (InsertStmt) stmt;
                    TableName tableName = insertStmt.getTableName();
                    Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(stmt.getTableName().getDb());
                    OlapTable tbl = ((OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                            .getTable(testDb.getFullName(), tableName.getTbl()));
                    for (Partition partition : tbl.getPartitions()) {
                        if (insertStmt.getTargetPartitionIds().contains(partition.getId())) {
                            long version = partition.getDefaultPhysicalPartition().getVisibleVersion() + 1;
                            partition.getDefaultPhysicalPartition().setVisibleVersion(version, System.currentTimeMillis());
                            MaterializedIndex baseIndex = partition.getDefaultPhysicalPartition().getBaseIndex();
                            List<Tablet> tablets = baseIndex.getTablets();
                            for (Tablet tablet : tablets) {
                                List<Replica> replicas = ((LocalTablet) tablet).getImmutableReplicas();
                                for (Replica replica : replicas) {
                                    replica.updateVersionInfo(version, -1, version);
                                }
                            }
                        }
                    }
                }
            }
        };

        starRocksAssert.useDatabase("test")
                .withTable("CREATE TABLE `test`.`tbl_with_hour_partition` (\n" +
                        "  `k1` datetime,\n" +
                        "  `k2` int,\n" +
                        "  `v1` string\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`k1`, `k2`)\n" +
                        "PARTITION BY RANGE(`k1`)\n" +
                        "(\n" +
                        "PARTITION p2023041015 VALUES [(\"2023-04-10 15:00:00\"), (\"2023-04-10 16:00:00\")),\n" +
                        "PARTITION p2023041016 VALUES [(\"2023-04-10 16:00:00\"), (\"2023-04-10 17:00:00\")),\n" +
                        "PARTITION p2023041017 VALUES [(\"2023-04-10 17:00:00\"), (\"2023-04-10 18:00:00\")),\n" +
                        "PARTITION p2023041018 VALUES [(\"2023-04-10 18:00:00\"), (\"2023-04-10 19:00:00\"))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(`v1`) BUCKETS 3\n" +
                        "PROPERTIES (\"replication_num\" = \"1\");")
                .withMaterializedView("CREATE MATERIALIZED VIEW `mv_with_hour_partiton`\n" +
                        "PARTITION BY (date_trunc('hour', `k1`))\n" +
                        "REFRESH DEFERRED MANUAL \n" +
                        "DISTRIBUTED BY HASH(`k1`) BUCKETS 3\n" +
                        "PROPERTIES (\"replication_num\" = \"1\", \"partition_refresh_number\"=\"1\")\n" +
                        "AS\n" +
                        "SELECT \n" +
                        "k1,\n" +
                        "count(DISTINCT `v1`) AS `v` \n" +
                        "FROM `test`.`tbl_with_hour_partition`\n" +
                        "group by k1;");
        starRocksAssert.updateTablePartitionVersion("test", "tbl_with_hour_partition", 2);
        starRocksAssert.refreshMvPartition("REFRESH MATERIALIZED VIEW test.mv_with_hour_partiton \n" +
                "PARTITION START (\"2023-04-10 15:00:00\") END (\"2023-04-10 17:00:00\")");
        MaterializedView mv1 = getMv("test", "mv_with_hour_partiton");
        Map<Long, Map<String, MaterializedView.BasePartitionInfo>> versionMap1 =
                mv1.getRefreshScheme().getAsyncRefreshContext().getBaseTableVisibleVersionMap();
        Assertions.assertEquals(1, versionMap1.size());
        Set<String> partitions1 = versionMap1.values().iterator().next().keySet();
        Assertions.assertEquals(2, partitions1.size());

        starRocksAssert.useDatabase("test")
                .withTable("CREATE TABLE `test`.`tbl_with_day_partition` (\n" +
                        "  `k1` date,\n" +
                        "  `k2` int,\n" +
                        "  `v1` string\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`k1`, `k2`)\n" +
                        "PARTITION BY RANGE(`k1`)\n" +
                        "(\n" +
                        "PARTITION p20230410 VALUES [(\"2023-04-10\"), (\"2023-04-11\")),\n" +
                        "PARTITION p20230411 VALUES [(\"2023-04-11\"), (\"2023-04-12\")),\n" +
                        "PARTITION p20230412 VALUES [(\"2023-04-12\"), (\"2023-04-13\")),\n" +
                        "PARTITION p20230413 VALUES [(\"2023-04-13\"), (\"2023-04-14\"))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(`v1`) BUCKETS 3\n" +
                        "PROPERTIES (\"replication_num\" = \"1\");")
                .withMaterializedView("CREATE MATERIALIZED VIEW `mv_with_day_partiton`\n" +
                        "PARTITION BY (date_trunc('day', `k1`))\n" +
                        "REFRESH DEFERRED MANUAL \n" +
                        "DISTRIBUTED BY HASH(`k1`) BUCKETS 3\n" +
                        "PROPERTIES (\"replication_num\" = \"1\", \"partition_refresh_number\"=\"1\")\n" +
                        "AS\n" +
                        "SELECT \n" +
                        "k1,\n" +
                        "count(DISTINCT `v1`) AS `v` \n" +
                        "FROM `test`.`tbl_with_day_partition`\n" +
                        "group by k1;");
        starRocksAssert.updateTablePartitionVersion("test", "tbl_with_day_partition", 2);
        starRocksAssert.refreshMvPartition("REFRESH MATERIALIZED VIEW test.mv_with_day_partiton \n" +
                "PARTITION START (\"2023-04-10\") END (\"2023-04-13\")");
        MaterializedView mv2 = getMv("test", "mv_with_day_partiton");
        Map<Long, Map<String, MaterializedView.BasePartitionInfo>> versionMap2 =
                mv2.getRefreshScheme().getAsyncRefreshContext().getBaseTableVisibleVersionMap();
        Assertions.assertEquals(1, versionMap2.size());
        Set<String> partitions2 = versionMap2.values().iterator().next().keySet();
        Assertions.assertEquals(3, partitions2.size());
    }

    @Test
    public void testDropBaseTablePartitionRemoveVersionMap() throws Exception {
        new MockUp<StmtExecutor>() {
            @Mock
            public void handleDMLStmt(ExecPlan execPlan, DmlStmt stmt) throws Exception {
                if (stmt instanceof InsertStmt) {
                    InsertStmt insertStmt = (InsertStmt) stmt;
                    TableName tableName = insertStmt.getTableName();
                    Database testDb = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(stmt.getTableName().getDb());
                    OlapTable tbl = ((OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                            .getTable(testDb.getFullName(), tableName.getTbl()));
                    for (Partition partition : tbl.getPartitions()) {
                        if (insertStmt.getTargetPartitionIds().contains(partition.getId())) {
                            long version = partition.getDefaultPhysicalPartition().getVisibleVersion() + 1;
                            partition.getDefaultPhysicalPartition().setVisibleVersion(version, System.currentTimeMillis());
                            MaterializedIndex baseIndex = partition.getDefaultPhysicalPartition().getBaseIndex();
                            List<Tablet> tablets = baseIndex.getTablets();
                            for (Tablet tablet : tablets) {
                                List<Replica> replicas = ((LocalTablet) tablet).getImmutableReplicas();
                                for (Replica replica : replicas) {
                                    replica.updateVersionInfo(version, -1, version);
                                }
                            }
                        }
                    }
                }
            }
        };

        starRocksAssert.useDatabase("test")
                .withTable("CREATE TABLE `test`.`tbl_with_partition` (\n" +
                        "  `k1` date,\n" +
                        "  `k2` int,\n" +
                        "  `v1` string\n" +
                        ") ENGINE=OLAP\n" +
                        "DUPLICATE KEY(`k1`, `k2`)\n" +
                        "PARTITION BY RANGE(`k1`)\n" +
                        "(\n" +
                        "PARTITION p20230410 VALUES [(\"2023-04-10\"), (\"2023-04-11\")),\n" +
                        "PARTITION p20230411 VALUES [(\"2023-04-11\"), (\"2023-04-12\")),\n" +
                        "PARTITION p20230412 VALUES [(\"2023-04-12\"), (\"2023-04-13\")),\n" +
                        "PARTITION p20230413 VALUES [(\"2023-04-13\"), (\"2023-04-14\"))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(`v1`) BUCKETS 3\n" +
                        "PROPERTIES (\"replication_num\" = \"1\");")
                .withMaterializedView("CREATE MATERIALIZED VIEW `mv_with_partition`\n" +
                        "PARTITION BY (date_trunc('day', k1))\n" +
                        "REFRESH DEFERRED MANUAL \n" +
                        "DISTRIBUTED BY HASH(`k1`) BUCKETS 3\n" +
                        "PROPERTIES (\"replication_num\" = \"1\", \"partition_refresh_number\"=\"1\")\n" +
                        "AS\n" +
                        "SELECT \n" +
                        "k1,\n" +
                        "count(DISTINCT `v1`) AS `v` \n" +
                        "FROM `test`.`tbl_with_partition`\n" +
                        "group by k1;");
        starRocksAssert.updateTablePartitionVersion("test", "tbl_with_partition", 2);
        starRocksAssert.refreshMvPartition("REFRESH MATERIALIZED VIEW test.mv_with_partition \n" +
                "PARTITION START (\"2023-04-10\") END (\"2023-04-14\")");
        MaterializedView mv = getMv("test", "mv_with_partition");
        Map<Long, Map<String, MaterializedView.BasePartitionInfo>> versionMap =
                mv.getRefreshScheme().getAsyncRefreshContext().getBaseTableVisibleVersionMap();
        Assertions.assertEquals(1, versionMap.size());
        Set<String> partitions = versionMap.values().iterator().next().keySet();
        //        Assert.assertEquals(4, partitions.size());

        starRocksAssert.alterMvProperties(
                "alter materialized view test.mv_with_partition set (\"partition_ttl_number\" = \"1\")");

        DynamicPartitionScheduler dynamicPartitionScheduler = GlobalStateMgr.getCurrentState()
                .getDynamicPartitionScheduler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable tbl =
                (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv_with_partition");
        dynamicPartitionScheduler.registerTtlPartitionTable(db.getId(), tbl.getId());
        dynamicPartitionScheduler.runOnceForTest();
        starRocksAssert.refreshMvPartition("REFRESH MATERIALIZED VIEW test.mv_with_partition \n" +
                "PARTITION START (\"2023-04-10\") END (\"2023-04-14\")");
        Assertions.assertEquals(Sets.newHashSet("p20230413"), versionMap.values().iterator().next().keySet());

        starRocksAssert
                .useDatabase("test")
                .dropMaterializedView("mv_with_partition")
                .dropTable("tbl_with_partition");
    }

    private Set<LocalDate> buildTimePartitions(String tableName, OlapTable tbl, int partitionCount) throws Exception {
        LocalDate currentDate = LocalDate.now();
        Set<LocalDate> partitionBounds = Sets.newHashSet();
        for (int i = 0; i < partitionCount; i++) {
            LocalDate lowerBound = currentDate.minus(Period.ofMonths(i + 1));
            LocalDate upperBound = currentDate.minus(Period.ofMonths(i));
            partitionBounds.add(lowerBound);
            String partitionName = String.format("p_%d_%d", lowerBound.getYear(), lowerBound.getMonthValue());
            String addPartition = String.format("alter table %s add partition p%s values [('%s'), ('%s')) ",
                    tableName, partitionName, lowerBound.toString(), upperBound);
            starRocksAssert.getCtx().executeSql(addPartition);
        }
        return partitionBounds;
    }

    @Test
    public void testMaterializedViewPartitionTTL() throws Exception {
        String dbName = "test";
        String tableName = "test.tbl1";
        starRocksAssert.withTable("CREATE TABLE " + tableName +
                "(\n" +
                "    k1 date,\n" +
                "    v1 int \n" +
                ")\n" +
                "PARTITION BY RANGE(k1)\n" +
                "(\n" +
                "    PARTITION p1 values less than('2022-06-01'),\n" +
                "    PARTITION p2 values less than('2022-07-01'),\n" +
                "    PARTITION p3 values less than('2022-08-01'),\n" +
                "    PARTITION p4 values less than('2022-09-01')\n" +
                ")\n" +
                "DISTRIBUTED BY HASH (k1) BUCKETS 3\n" +
                "PROPERTIES\n" +
                "(\n" +
                "    'replication_num' = '1'\n" +
                ");");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW test.mv_ttl_mv1\n" +
                " REFRESH ASYNC " +
                " PARTITION BY k1\n" +
                " PROPERTIES('partition_ttl'='2 month')" +
                " AS SELECT k1, v1 FROM test.tbl1");

        DynamicPartitionScheduler dynamicPartitionScheduler = GlobalStateMgr.getCurrentState()
                .getDynamicPartitionScheduler();
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
        OlapTable tbl = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv_ttl_mv1");
        Set<LocalDate> addedPartitions = buildTimePartitions(tableName, tbl, 10);

        // Build expectations
        Function<LocalDate, String> formatPartitionName = (dt) ->
                String.format("pp_%d_%d", dt.getYear(), dt.getMonthValue());
        Comparator<LocalDate> cmp = Comparator.comparing(x -> x, Comparator.reverseOrder());
        Function<Integer, Set<String>> expect = (n) -> addedPartitions.stream()
                .sorted(cmp).limit(n)
                .map(formatPartitionName)
                .collect(Collectors.toSet());

        // initial partitions should consider the ttl
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");

        Assertions.assertEquals(expect.apply(2), tbl.getPartitionNames());

        // normal ttl
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='2 month')");
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");
        dynamicPartitionScheduler.runOnceForTest();
        Assertions.assertEquals(expect.apply(2), tbl.getPartitionNames());

        // large ttl
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='10 year')");
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");
        dynamicPartitionScheduler.runOnceForTest();
        Assertions.assertEquals(14, tbl.getPartitions().size(), tbl.getRangePartitionMap().toString());

        // tiny ttl
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='1 day')");
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");
        dynamicPartitionScheduler.runOnceForTest();
        Assertions.assertEquals(expect.apply(1), tbl.getPartitionNames());

        // zero ttl
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='0 day')");
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");
        dynamicPartitionScheduler.runOnceForTest();
        Assertions.assertEquals(14, tbl.getPartitions().size(), tbl.getRangePartitionMap().toString());
        Assertions.assertEquals("PT0S", tbl.getTableProperty().getPartitionTTL().toString());

        // tiny ttl
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='24 hour')");
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");
        dynamicPartitionScheduler.runOnceForTest();
        Assertions.assertEquals(1, tbl.getPartitions().size(), tbl.getRangePartitionMap().toString());
        Assertions.assertEquals(expect.apply(1), tbl.getPartitionNames());

        // the ttl cross two partitions
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='32 day')");
        cluster.runSql(dbName, "refresh materialized view test.mv_ttl_mv1 with sync mode");
        dynamicPartitionScheduler.runOnceForTest();
        Assertions.assertEquals(expect.apply(2), tbl.getPartitionNames());
        Assertions.assertEquals(2, tbl.getPartitions().size(), tbl.getRangePartitionMap().toString());

        // corner cases
        Assertions.assertThrows(Exception.class,
                () -> cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='error')"));
        Assertions.assertThrows(Exception.class,
                () -> cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='day')"));
        Assertions.assertThrows(Exception.class,
                () -> cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='0')"));
        Assertions.assertEquals("P32D", tbl.getTableProperty().getPartitionTTL().toString());
        cluster.runSql(dbName, "alter materialized view test.mv_ttl_mv1 set ('partition_ttl'='0 day')");
        Assertions.assertEquals("PT0S", tbl.getTableProperty().getPartitionTTL().toString());

    }

    @Test
    public void testMvOnUnion_Unaligned() throws Exception {
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t1 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    observation_date DATE NOT NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(observation_date) (" +
                "   PARTITION p1_20240321 VALUES LESS THAN ('2024-03-21'), \n" +
                "   PARTITION p1_20240322 VALUES LESS THAN ('2024-03-22') \n" +
                ") ");
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t2 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    observation_date DATE NOT NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(observation_date) (" +
                "   PARTITION p2_20240321 VALUES LESS THAN ('2024-03-21'), \n" +
                "   PARTITION p2_20240322 VALUES LESS THAN ('2024-03-22'), \n" +
                "   PARTITION p2_20240323 VALUES LESS THAN ('2024-03-23') \n" +
                ") ");

        starRocksAssert.withRefreshedMaterializedView("CREATE MATERIALIZED VIEW mv_union_1 \n" +
                "PARTITION BY date_trunc('day', observation_date)\n" +
                "DISTRIBUTED BY HASH(leg_id)\n" +
                "REFRESH ASYNC\n" +
                "AS \n" +
                "SELECT * FROM mv_union_t1 t1\n" +
                "UNION ALL\n" +
                "SELECT * FROM mv_union_t2 t2\n");

        String mvName = "mv_union_1";
        MaterializedView mv = starRocksAssert.getMv("test", "mv_union_1");
        Assertions.assertEquals(
                Sets.newHashSet("p00010101_20240321", "p20240321_20240322", "p20240322_20240323"),
                mv.getPartitionNames());

        // cleanup
        starRocksAssert.dropTable("mv_union_t1");
        starRocksAssert.dropTable("mv_union_t2");
        starRocksAssert.dropMaterializedView(mvName);
    }

    @Test
    public void testUnionSelf() throws Exception {
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t1 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    datekey DATE NOT NULL,\n" +
                "    v1 int(11) NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(datekey) (" +
                "   PARTITION p1_20240321 VALUES LESS THAN ('2024-03-21'), \n" +
                "   PARTITION p1_20240322 VALUES LESS THAN ('2024-03-22') \n" +
                ") ");

        starRocksAssert.withRefreshedMaterializedView("CREATE MATERIALIZED VIEW mv_union_1 \n" +
                "PARTITION BY p_time\n" +
                "REFRESH ASYNC\n" +
                "AS \n" +
                "select date_trunc(\"day\", a.datekey) as p_time FROM mv_union_t1 a group by p_time \n" +
                "UNION ALL\n" +
                "select date_trunc(\"day\", b.datekey) as p_time FROM mv_union_t1 b group by p_time \n");

        String mvName = "mv_union_1";
        MaterializedView mv = starRocksAssert.getMv("test", mvName);
        Assertions.assertTrue(starRocksAssert.waitRefreshFinished(mv.getId()));
        Assertions.assertEquals(
                Sets.newHashSet("p00010101_20240321", "p20240321_20240322"),
                mv.getPartitionNames());

        // cleanup
        starRocksAssert.dropTable("mv_union_t1");
        starRocksAssert.dropMaterializedView(mvName);
    }

    /**
     * Intersected UNION partition must be same, otherwise will report error
     */
    @Test
    public void testMvOnUnion_IntersectedPartition1() throws Exception {
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t1 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    observation_date DATE NOT NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(observation_date) (" +
                "   PARTITION p1_20240321 VALUES LESS THAN ('2024-03-21'), \n" +
                "   PARTITION p1_20240322 VALUES LESS THAN ('2024-03-22') \n" +
                ") ");
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t2 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    observation_date DATE NOT NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(observation_date) (" +
                "   PARTITION p2_20240322 VALUES LESS THAN ('2024-03-22'), \n" +
                "   PARTITION p2_20240323 VALUES LESS THAN ('2024-03-23') \n" +
                ") ");
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t3 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    observation_date DATE NOT NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(observation_date) (" +
                "   PARTITION p2_20240322 VALUES LESS THAN ('2024-03-21'), \n" +
                "   PARTITION p2_20240323 VALUES LESS THAN ('2024-04-21') \n" +
                ") ");
        starRocksAssert.withTable("CREATE TABLE IF NOT EXISTS mv_union_t4 (\n" +
                "    leg_id VARCHAR(100) NOT NULL,\n" +
                "    cabin_class VARCHAR(1) NOT NULL,\n" +
                "    observation_date DATE NOT NULL\n" +
                ")\n" +
                "DUPLICATE KEY(leg_id, cabin_class)\n" +
                "PARTITION BY RANGE(observation_date) (" +
                "   PARTITION p2_20240321 VALUES LESS THAN ('2024-03-21'), \n" +
                "   PARTITION p2_20240322 VALUES LESS THAN ('2024-03-22') \n" +
                ") ");

        {
            starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv1 \n" +
                    "PARTITION BY date_trunc('day', observation_date)\n" +
                    "DISTRIBUTED BY HASH(leg_id)\n" +
                    "REFRESH ASYNC\n" +
                    "AS \n" +
                    "SELECT * FROM mv_union_t1 t1\n" +
                    "UNION ALL\n" +
                    "SELECT * FROM mv_union_t2 t2\n", () -> {
            });
        }

        {
            starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv2 \n" +
                    "PARTITION BY date_trunc('day', observation_date)\n" +
                    "DISTRIBUTED BY HASH(leg_id)\n" +
                    "REFRESH ASYNC\n" +
                    "AS \n" +
                    "SELECT * FROM mv_union_t1 t1\n" +
                    "UNION ALL\n" +
                    "SELECT * FROM mv_union_t3 t2\n", () -> {
            });
        }

        {
            // create succeed, but refresh fail
            starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv2 \n" +
                    "PARTITION BY date_trunc('day', observation_date)\n" +
                    "REFRESH ASYNC\n" +
                    "AS \n" +
                    "SELECT * FROM mv_union_t1 t1\n" +
                    "UNION ALL\n" +
                    "SELECT * FROM mv_union_t4 t2\n", () -> {
            });
            // add partition to child table
            starRocksAssert.ddl("alter table mv_union_t4 add partition p20240325 values less than ('2024-03-25')");
            starRocksAssert.ddl("alter table mv_union_t1 add partition p20240326 values less than ('2024-03-26')");
        }

        // cleanup
        starRocksAssert.dropTable("mv_union_t1");
        starRocksAssert.dropTable("mv_union_t2");
        starRocksAssert.dropMaterializedView("mv1");
    }

    @Test
    public void testMvOnUnion_IntersectedPartition2() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY RANGE(k1)\n" +
                        "(\n" +
                        "    PARTITION p1 values [('2022-02-01'),('2022-02-02')),\n" +
                        "    PARTITION p2 values [('2022-02-02'),('2022-02-03')),\n" +
                        "    PARTITION p3 values [('2022-02-03'),('2022-02-04')),\n" +
                        "    PARTITION p4 values [('2022-02-04'),('2022-02-05'))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY RANGE(k1)\n" +
                        "(\n" +
                        "    PARTITION p2 values [('2022-02-02'),('2022-02-03')),\n" +
                        "    PARTITION p4 values [('2022-03-02'),('2022-03-04'))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
        starRocksAssert.withMaterializedView("CREATE MATERIALIZED VIEW mv1 \n" +
                "PARTITION BY date_trunc('day', k1)\n" +
                "DISTRIBUTED BY RANDOM\n" +
                "REFRESH ASYNC\n" +
                "AS \n" +
                "SELECT * FROM t1\n" +
                "UNION ALL\n" +
                "SELECT * FROM t2\n");
        starRocksAssert.dropTable("t1");
        starRocksAssert.dropTable("t2");
        starRocksAssert.dropMaterializedView("mv1");
    }

    @Test
    public void testMvOnUnion_IntersectedPartition3() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
        // auto create partitions for t1/t2
        {
            String sql1 = "INSERT INTO t1 VALUES ('2022-02-01', 1, 1), ('2022-02-02', 2, 2), " +
                    "('2022-02-03', 3, 3), ('2022-02-04', 4, 4)";
            executeInsertSql(connectContext, sql1);

            String sql2 = "INSERT INTO t2 values ('2022-02-02', 2, 2), ('2022-03-02', 5, 5)";
            executeInsertSql(connectContext, sql2);
        }

        {
            withRefreshedMV("CREATE MATERIALIZED VIEW mv1 \n" +
                    "PARTITION BY date_trunc('day', k1)\n" +
                    "DISTRIBUTED BY RANDOM\n" +
                    "REFRESH ASYNC\n" +
                    "AS \n" +
                    "SELECT * FROM t1\n" +
                    "UNION ALL\n" +
                    "SELECT * FROM t2\n", () -> {

                Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
                MaterializedView mv =
                        (MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv1");
                System.out.println(mv.getPartitionNames());
            });
        }
        starRocksAssert.dropTable("t1");
        starRocksAssert.dropTable("t2");
        starRocksAssert.dropMaterializedView("mv1");
    }

    @Test
    public void testMvOnUnion_IntersectedPartition4() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
        // auto create partitions for t1/t2
        {
            String sql1 = "INSERT INTO t1 VALUES ('2022-02-01', 1, 1), ('2022-02-02', 2, 2), " +
                    "('2022-02-03', 3, 3), ('2022-02-04', 4, 4)";
            executeInsertSql(connectContext, sql1);

            String sql2 = "INSERT INTO t2 values ('2022-03-02', 2, 2), ('2022-03-02', 5, 5)";
            executeInsertSql(connectContext, sql2);
        }

        {
            withRefreshedMV("CREATE MATERIALIZED VIEW mv1 \n" +
                    "PARTITION BY date_trunc('day', k1)\n" +
                    "DISTRIBUTED BY RANDOM\n" +
                    "REFRESH ASYNC\n" +
                    "AS \n" +
                    "SELECT * FROM t1\n" +
                    "UNION ALL\n" +
                    "SELECT * FROM t2\n", () -> {
                Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
                MaterializedView mv =
                        (MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv1");
                Assertions.assertEquals(2, mv.getPartitionExprMaps().size());
                System.out.println(mv.getPartitionNames());
            });
        }
        starRocksAssert.dropTable("t1");
        starRocksAssert.dropTable("t2");
        starRocksAssert.dropMaterializedView("mv1");
    }

    @Test
    public void testMvOnUnion_IntersectedPartition5() throws Exception {
        starRocksAssert
                .withTable("CREATE TABLE t1 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');")
                .withTable("CREATE TABLE t2 \n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PARTITION BY date_trunc('day', k1)\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");
        // auto create partitions for t1/t2
        {
            String sql1 = "INSERT INTO t1 VALUES ('2022-02-01', 1, 1), ('2022-02-02', 2, 2), " +
                    "('2022-02-03', 3, 3), ('2022-02-04', 4, 4)";
            executeInsertSql(connectContext, sql1);

            String sql2 = "INSERT INTO t2 values ('2022-03-02', 2, 2), ('2022-03-02', 5, 5)";
            executeInsertSql(connectContext, sql2);
        }

        {
            withRefreshedMV("CREATE MATERIALIZED VIEW mv1 \n" +
                    "PARTITION BY date_trunc('day', k1)\n" +
                    "DISTRIBUTED BY RANDOM\n" +
                    "REFRESH ASYNC\n" +
                    "AS \n" +
                    "select k1 from (SELECT * FROM t1 UNION ALL SELECT * FROM t2) t group by k1\n", () -> {
                Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("test");
                MaterializedView mv =
                        (MaterializedView) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getFullName(), "mv1");
                System.out.println(mv.getPartitionExprMaps());
                Assertions.assertEquals(2, mv.getPartitionExprMaps().size());
                System.out.println(mv.getPartitionNames());
            });
        }
        starRocksAssert.dropTable("t1");
        starRocksAssert.dropTable("t2");
        starRocksAssert.dropMaterializedView("mv1");
    }

    @Test
    public void testRefreshListPartitionMV1() {
        starRocksAssert.withMaterializedView("create materialized view test_mv1\n" +
                        "partition by dt \n" +
                        "distributed by random \n" +
                        "REFRESH DEFERRED MANUAL \n" +
                        "properties ('partition_refresh_number' = '1')" +
                        "as select dt, province, sum(age) from s2 group by dt, province;",
                (obj) -> {
                    {
                        String sql = "REFRESH MATERIALIZED VIEW test_mv1 PARTITION ('20240101') FORCE;";
                        RefreshMaterializedViewStatement statement =
                                (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
                        Assertions.assertTrue(statement.isForceRefresh());
                        Assertions.assertNull(statement.getPartitionRangeDesc());
                        Set<PListCell> expect = ImmutableSet.of(
                                new PListCell("20240101")
                        );
                        Assertions.assertEquals(expect, statement.getPartitionListDesc());
                    }
                    {
                        String sql = "REFRESH MATERIALIZED VIEW test_mv1 PARTITION ('20240101', '20240102') FORCE;";
                        RefreshMaterializedViewStatement statement =
                                (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
                        Assertions.assertTrue(statement.isForceRefresh());
                        Assertions.assertNull(statement.getPartitionRangeDesc());
                        Set<PListCell> expect = ImmutableSet.of(
                                        new PListCell("20240101"),
                                        new PListCell("20240102")
                                );
                        Assertions.assertEquals(expect, statement.getPartitionListDesc());
                    }
                    // multi partition columns may be supported in the future
                    {
                        String sql = "REFRESH MATERIALIZED VIEW test_mv1 PARTITION (('20240101', 'beijing'), ('20240101', " +
                                "'nanjing')) FORCE;";
                        try {
                            RefreshMaterializedViewStatement statement =
                                    (RefreshMaterializedViewStatement) UtFrameUtils.parseStmtWithNewParser(sql, connectContext);
                            Assertions.fail();
                        } catch (Exception e) {
                           Assertions.assertTrue(e.getMessage().contains("Partition column size 1 is not match with input partition"
                                   + " value's size 2"));
                        }
                    }
                });
    }

    @Test
    public void testTruncateTableInDiffDb() throws Exception {
        starRocksAssert
                .createDatabaseIfNotExists("trunc_db")
                .useDatabase("trunc_db")
                .withTable("CREATE TABLE trunc_db_t1 \n" +
                        "(\n" +
                        "    k1 int,\n" +
                        "    v1 int\n" +
                        ")\n" +
                        "PROPERTIES('replication_num' = '1');");
        starRocksAssert.createDatabaseIfNotExists("mv_db")
                .useDatabase("mv_db")
                .withMaterializedView("CREATE MATERIALIZED VIEW test_mv\n"
                        + "DISTRIBUTED BY HASH(`k1`)\n"
                        + "REFRESH DEFERRED ASYNC\n"
                        + "AS SELECT k1 from trunc_db.trunc_db_t1;");

        executeInsertSql(connectContext, "insert into trunc_db.trunc_db_t1 values(2, 10)");
        MaterializedView mv1 = getMv("mv_db", "test_mv");

        Table table = getTable("trunc_db", "trunc_db_t1");
        // Simulate writing to a non-existent MV
        table.addRelatedMaterializedView(new MvId(1,1));
        String truncateStr = "truncate table trunc_db.trunc_db_t1;";
        TruncateTableStmt truncateTableStmt = (TruncateTableStmt) UtFrameUtils.parseStmtWithNewParser(truncateStr, connectContext);
        GlobalStateMgr.getCurrentState().getLocalMetastore().truncateTable(truncateTableStmt, connectContext);
        Assertions.assertTrue(starRocksAssert.waitRefreshFinished(mv1.getId()));

        starRocksAssert.dropTable("trunc_db.trunc_db_t1");
        starRocksAssert.dropMaterializedView("mv_db.test_mv");
    }

    @Test
    public void testDropPartitionTableInDiffDb() throws Exception {
        starRocksAssert
                .createDatabaseIfNotExists("drop_db")
                .useDatabase("drop_db")
                .withTable("CREATE TABLE tbl_with_mv\n" +
                        "(\n" +
                        "    k1 date,\n" +
                        "    k2 int,\n" +
                        "    v1 int sum\n" +
                        ")\n" +
                        "PARTITION BY RANGE(k1)\n" +
                        "(\n" +
                        "    PARTITION p1 values [('2022-02-01'),('2022-02-16')),\n" +
                        "    PARTITION p2 values [('2022-02-16'),('2022-03-01'))\n" +
                        ")\n" +
                        "DISTRIBUTED BY HASH(k2) BUCKETS 3\n" +
                        "PROPERTIES('replication_num' = '1');");

        starRocksAssert.createDatabaseIfNotExists("drop_mv_db")
                .useDatabase("drop_mv_db")
                .withMaterializedView("CREATE MATERIALIZED VIEW test_mv\n"
                        + "DISTRIBUTED BY HASH(`k2`)\n"
                        + "REFRESH DEFERRED ASYNC\n"
                        + "AS select k1, k2, v1  from drop_db.tbl_with_mv;");

        executeInsertSql(connectContext, "insert into drop_db.tbl_with_mv partition(p2) values(\"2022-02-20\", 2, 10)");
        MaterializedView mv1 = getMv("drop_mv_db", "test_mv");
        OlapTable table = (OlapTable) getTable("drop_db", "tbl_with_mv");
        Partition p1 = table.getPartition("p1");
        DropPartitionClause dropPartitionClause = new DropPartitionClause(false, p1.getName(), false, true);
        dropPartitionClause.setResolvedPartitionNames(ImmutableList.of(p1.getName()));
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("drop_db");
        GlobalStateMgr.getCurrentState().getLocalMetastore().dropPartition(db, table, dropPartitionClause);
        Assertions.assertTrue(starRocksAssert.waitRefreshFinished(mv1.getId()));
        starRocksAssert.dropTable("drop_db.tbl_with_mv");
        starRocksAssert.dropMaterializedView("drop_mv_db.test_mv");
    }


    @Test
    public void testCreateExcludedRefreshTablesSupportMV() throws Exception {
        starRocksAssert
                .createDatabaseIfNotExists("mvtest")
                .useDatabase("mvtest")
                .withTable("CREATE TABLE IF NOT EXISTS mvtest.par_tbl1\n" +
                        "(\n" +
                        "    datekey DATETIME,\n" +
                        "    item_id STRING,\n" +
                        "    v1      INT\n" +
                        ")PRIMARY KEY (`datekey`,`item_id`)\n" +
                        "    PARTITION BY date_trunc('day', `datekey`);");
        executeInsertSql(connectContext, "INSERT INTO mvtest.par_tbl1 values ('2025-01-01', '1', 1);");
        executeInsertSql(connectContext, "INSERT INTO mvtest.par_tbl1 values ('2025-01-02', '1', 1);");

        starRocksAssert
                .useDatabase("mvtest")
                .withTable("CREATE TABLE IF NOT EXISTS mvtest.par_tbl2\n" +
                        "(\n" +
                        "    datekey DATETIME,\n" +
                        "    item_id STRING,\n" +
                        "    v1      INT\n" +
                        ")PRIMARY KEY (`datekey`,`item_id`)\n" +
                        "    PARTITION BY date_trunc('day', `datekey`);");
        executeInsertSql(connectContext, "INSERT INTO mvtest.par_tbl2 values ('2025-01-01', '1', 2);");
        executeInsertSql(connectContext, "INSERT INTO mvtest.par_tbl2 values ('2025-01-02', '1', 1);");

        starRocksAssert
                .useDatabase("mvtest")
                .withTable("CREATE TABLE IF NOT EXISTS mvtest.dim_data\n" +
                        "(\n" +
                        "    item_id STRING,\n" +
                        "    v1 INT\n" +
                        ")PRIMARY KEY (`item_id`);");
        executeInsertSql(connectContext, "INSERT INTO mvtest.dim_data values ('1', 4);");

        starRocksAssert.useDatabase("mvtest")
                .withMaterializedView("CREATE\n" +
                        "MATERIALIZED VIEW mvtest.mv_dim_data1\n" +
                        "REFRESH ASYNC EVERY(INTERVAL 60 MINUTE)\n" +
                        "AS\n" +
                        "select *\n" +
                        "from mvtest.dim_data;");

        starRocksAssert.useDatabase("mvtest")
                .withMaterializedView("CREATE\n" +
                        "MATERIALIZED VIEW mvtest.mv_test1\n" +
                        "REFRESH ASYNC EVERY(INTERVAL 60 MINUTE)\n" +
                        "PARTITION BY p_time\n" +
                        "PROPERTIES (\n" +
                        "\"excluded_trigger_tables\" = \"mvtest.mv_dim_data1\",\n" +
                        "\"excluded_refresh_tables\" = \"mvtest.mv_dim_data1\",\n" +
                        "\"partition_refresh_number\" = \"1\"\n" +
                        ")\n" +
                        "AS\n" +
                        "select date_trunc(\"day\", a.datekey) as p_time, sum(a.v1) + sum(b.v1) as v1\n" +
                        "from mvtest.par_tbl1 a\n" +
                        "         left join mvtest.par_tbl2 b on a.datekey = b.datekey and a.item_id = b.item_id\n" +
                        "         left join mvtest.mv_dim_data1 d on a.item_id = d.item_id\n" +
                        "group by date_trunc(\"day\", a.datekey), a.item_id;");

        starRocksAssert.refreshMV("refresh materialized view mvtest.mv_test1");
        MaterializedView mv = getMv("mvtest", "mv_test1");
        Assertions.assertTrue(starRocksAssert.waitRefreshFinished(mv.getId()));

        starRocksAssert.dropTable("par_tbl1");
        starRocksAssert.dropTable("par_tbl2");
        starRocksAssert.dropTable("dim_data");
        starRocksAssert.dropMaterializedView("mv_dim_data1");
        starRocksAssert.dropMaterializedView("mv_test1");
    }
}
