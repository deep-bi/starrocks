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


package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.SubqueryUtils;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalApplyOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalFilterOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.List;
import java.util.Map;

// Pattern:
//      ApplyNode
//      /      \
//  LEFT     Aggregate(scalar aggregation)
//               \
//               Project
//                 \
//                 Filter
//                   \
//                   ....
//
// Before:
//      ApplyNode
//      /      \
//  LEFT     Aggregate(scalar aggregation)
//               \
//               Project
//                 \
//                 Filter
//                   \
//                   ....
// After:
//      ApplyNode
//      /      \
//  LEFT     Aggregate(scalar aggregation)
//               \
//               Filter
//                 \
//                 Project
//                   \
//                   ....
//
public class PushDownApplyAggProjectFilterRule extends TransformationRule {
    public PushDownApplyAggProjectFilterRule() {
        super(RuleType.TF_PUSH_DOWN_APPLY_AGG, Pattern.create(OperatorType.LOGICAL_APPLY).addChildren(
                Pattern.create(OperatorType.PATTERN_LEAF),
                Pattern.create(OperatorType.LOGICAL_AGGR).addChildren(
                        Pattern.create(OperatorType.LOGICAL_PROJECT, OperatorType.PATTERN_LEAF))));
    }

    @Override
    public boolean check(OptExpression input, OptimizerContext context) {
        OptExpression project = input.inputAt(1).inputAt(0);
        OptExpression child = project.inputAt(0);
        if (child.getOp() instanceof LogicalProjectOperator) {
            // A wide decimal aggregate has one extra project above the original aggregate arguments.
            // Keep both projects so shared inputs, including volatile expressions, are still evaluated once.
            if (!context.getSessionVariable().isDecimalOverflowReduceScale() ||
                    project.getOp().hasLimit() || child.getOp().hasLimit() ||
                    !((LogicalProjectOperator) project.getOp()).getColumnRefMap().values().stream()
                            .anyMatch(value -> value instanceof CastOperator && value.getType().isDecimal256())) {
                return false;
            }
            child = child.inputAt(0);
        }

        if (!(child.getOp() instanceof LogicalFilterOperator) || !SubqueryUtils.containsCorrelationSubquery(input)) {
            return false;
        }

        LogicalAggregationOperator aggregate = (LogicalAggregationOperator) input.getInputs().get(1).getOp();

        // Don't support
        // 1. More grouping column and not Exists subquery
        // 2. Distinct aggregation(same with group by xxx)
        return aggregate.getGroupingKeys().isEmpty() || ((LogicalApplyOperator) input.getOp()).isExistential();
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        OptExpression aggOptExpression = input.getInputs().get(1);
        OptExpression projectExpression = aggOptExpression.getInputs().get(0);
        LogicalProjectOperator wideProject = null;
        if (projectExpression.inputAt(0).getOp() instanceof LogicalProjectOperator) {
            wideProject = (LogicalProjectOperator) projectExpression.getOp();
            projectExpression = projectExpression.inputAt(0);
        }
        OptExpression filterExpression = projectExpression.getInputs().get(0);

        LogicalApplyOperator apply = (LogicalApplyOperator) input.getOp();
        LogicalAggregationOperator aggregate = (LogicalAggregationOperator) aggOptExpression.getOp();
        LogicalProjectOperator lpo = (LogicalProjectOperator) projectExpression.getOp();
        LogicalFilterOperator lfo = (LogicalFilterOperator) filterExpression.getOp();

        List<ColumnRefOperator> filterInput = Utils.extractColumnRef(lfo.getPredicate());
        filterInput.removeAll(apply.getCorrelationColumnRefs());

        Map<ColumnRefOperator, ScalarOperator> newProjectMap = Maps.newHashMap();
        newProjectMap.putAll(lpo.getColumnRefMap());
        filterInput.forEach(d -> newProjectMap.putIfAbsent(d, d));

        projectExpression.getInputs().clear();
        projectExpression.getInputs().addAll(filterExpression.getInputs());

        OptExpression newProjectOptExpression =
                OptExpression.create(new LogicalProjectOperator(newProjectMap), filterExpression.getInputs());

        if (wideProject != null) {
            Map<ColumnRefOperator, ScalarOperator> wideColumns = Maps.newHashMap(wideProject.getColumnRefMap());
            filterInput.forEach(column -> wideColumns.putIfAbsent(column, column));
            newProjectOptExpression = OptExpression.create(new LogicalProjectOperator(wideColumns),
                    newProjectOptExpression);
        }

        OptExpression newFilterOptExpression =
                OptExpression.create(new LogicalFilterOperator(lfo.getPredicate()), newProjectOptExpression);

        OptExpression newAggOptExpression = OptExpression
                .create(new LogicalAggregationOperator(aggregate.getType(), aggregate.getGroupingKeys(),
                        aggregate.getAggregations()), newFilterOptExpression);

        OptExpression newApplyOptExpression =
                OptExpression.create(LogicalApplyOperator.builder().withOperator(apply).build(),
                        input.getInputs().get(0), newAggOptExpression);

        return Lists.newArrayList(newApplyOptExpression);
    }
}
