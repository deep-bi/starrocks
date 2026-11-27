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

package com.starrocks.sql.plan;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;

public class DecimalAggregateScalePlanTest extends PlanTestBase {
    private String plan(String hint, String expressions) throws Exception {
        return getFragmentPlan("EXPLAIN SELECT " + hint + " " + expressions + " FROM t0").toLowerCase(Locale.ROOT);
    }

    @Test
    public void groupedAndWindowAccumulatorsRetainTheInputScale() throws Exception {
        String modeOn = "/*+ SET_VAR(decimal_overflow_reduce_scale=true) */";
        String input = "CAST(v2 AS DECIMAL(38,37))";
        for (String expr : new String[] {"SUM(" + input + ")", "AVG(" + input + ")",
                "SUM(" + input + ") OVER (ORDER BY v1 ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)",
                "AVG(" + input + ") OVER (ORDER BY v1 ROWS BETWEEN 1 PRECEDING AND 1 PRECEDING)"}) {
            String enabled = plan(modeOn, expr);
            Assertions.assertTrue(enabled.contains("decimal256(76,37)"), enabled);
            Assertions.assertFalse(plan("", expr).contains("decimal256"));
        }
    }

    @Test
    public void filteredDistinctAggregatesWidenTheFilteredInput() throws Exception {
        String modeOn = "/*+ SET_VAR(decimal_overflow_reduce_scale=true) */";
        for (String name : new String[] {"SUM", "AVG"}) {
            String query = name + "(DISTINCT CAST(v2 AS DECIMAL(38,19))) FILTER (WHERE v1 > 0)";
            String enabled = plan(modeOn, query);
            Assertions.assertTrue(enabled.contains("decimal256(76,19)"), enabled);
            Assertions.assertTrue(enabled.contains("v1"), enabled);
            Assertions.assertFalse(plan("", query).contains("decimal256"));
        }
    }

    @Test
    public void correlatedAggregatesRetainTheirFilter() throws Exception {
        for (String name : new String[] {"SUM", "AVG"}) {
            String query = "p.v1, (SELECT " + name + "(CAST(c.v2 AS DECIMAL(38,19))) " +
                    "FROM t0 c WHERE c.v1 = p.v1) FROM t0 p";
            String enabled = getFragmentPlan("EXPLAIN SELECT /*+ SET_VAR(decimal_overflow_reduce_scale=true) */ " + query)
                    .toLowerCase(Locale.ROOT);
            Assertions.assertTrue(enabled.contains("decimal256(76,19)"), enabled);
            String disabled = getFragmentPlan("EXPLAIN SELECT " + query).toLowerCase(Locale.ROOT);
            Assertions.assertFalse(disabled.contains("decimal256"), disabled);
        }
    }

    @Test
    public void distinctWindowUsesOneDeduplicatedStateForSumAndCount() throws Exception {
        for (String window : new String[] {"PARTITION BY v1", "PARTITION BY v1 ORDER BY v3 " +
                "RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"}) {
            String arg = "DISTINCT CAST(v2 AS DECIMAL(38,19))";
            String query = "SUM(" + arg + ") OVER (" + window + "), AVG(" + arg + ") OVER (" + window + ")";
            for (String expressions : new String[] {query, query + ", COUNT(DISTINCT v3) OVER (" + window + ")"}) {
                String enabled = plan("/*+ SET_VAR(decimal_overflow_reduce_scale=true) */", expressions);
                Assertions.assertTrue(enabled.lines().anyMatch(line ->
                        line.contains("functions:") && line.contains("fused_multi_distinct_count_sum(")), enabled);
                Assertions.assertFalse(plan("", expressions).contains("decimal256"));
            }
        }
    }

    @Test
    public void pivotCanReturnGroupedAndGlobalDecimalAggregates() throws Exception {
        String pivot = " PIVOT (SUM(v) AS s, AVG(v) AS a FOR k IN (1 AS one, 2 AS two))";
        String values = "v2 AS k, CAST(v3 AS DECIMAL(38,18)) AS v FROM t0) t";
        String grouped = "g, one_s, one_a, two_s, two_a FROM (SELECT v1 AS g, " + values + pivot;
        String global = "one_s, one_a, two_s, two_a FROM (SELECT " + values + pivot;
        for (String query : new String[] {grouped, global}) {
            String enabled = getFragmentPlan(
                    "EXPLAIN SELECT /*+ SET_VAR(decimal_overflow_reduce_scale=true) */ " + query);
            Assertions.assertTrue(enabled.toLowerCase(Locale.ROOT).contains("decimal256"), enabled);
            String disabled = getFragmentPlan("EXPLAIN SELECT " + query);
            Assertions.assertFalse(disabled.toLowerCase(Locale.ROOT).contains("decimal256"), disabled);
        }
    }
}
