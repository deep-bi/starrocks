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

package com.starrocks.sql.optimizer.transformer;

import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.Expr;
import com.starrocks.catalog.AggregateFunction;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Type;
import com.starrocks.catalog.combinator.AggStateIf;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalProjectOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalWindowOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CaseWhenOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperatorUtil;
import com.starrocks.sql.optimizer.operator.scalar.SubfieldOperator;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.starrocks.sql.optimizer.transformer.DecimalArithmeticLowering.arithmetic;
import static com.starrocks.sql.optimizer.transformer.DecimalArithmeticLowering.checkedNarrow;
import static com.starrocks.sql.optimizer.transformer.DecimalArithmeticLowering.wideType;

/** Use the same wide partial sums for grouped and windowed DECIMAL aggregates. */
final class DecimalAggregateRewriter {
    private DecimalAggregateRewriter() {
    }

    static OptExprBuilder rewrite(OptExprBuilder child, LogicalOperator operator,
                                  Map<ColumnRefOperator, CallOperator> calls, ExpressionMapping outputMapping,
                                  ColumnRefFactory factory) {
        if (calls.values().stream().noneMatch(DecimalAggregateRewriter::needsWideSum)) {
            return new OptExprBuilder(operator, List.of(child), outputMapping);
        }

        Map<ColumnRefOperator, ScalarOperator> inputs = new LinkedHashMap<>();
        child.getRoot().getRowOutputInfo().getOutputColRefs().forEach(col -> inputs.put(col, col));
        Map<ColumnRefOperator, ScalarOperator> outputs = new LinkedHashMap<>();
        if (operator instanceof LogicalAggregationOperator aggregation) {
            aggregation.getGroupingKeys().forEach(col -> outputs.put(col, col));
        } else {
            outputs.putAll(inputs);
        }
        Map<ColumnRefOperator, CallOperator> aggregates = new LinkedHashMap<>();
        Map<ScalarOperator, ColumnRefOperator> wideInputs = new LinkedHashMap<>();
        Map<ColumnRefOperator, ColumnRefOperator> distinctWindowStates = new LinkedHashMap<>();
        for (var entry : calls.entrySet()) {
            CallOperator call = entry.getValue();
            ColumnRefOperator result = entry.getKey();
            if (!needsWideSum(call)) {
                aggregates.put(result, call);
                outputs.put(result, result);
                continue;
            }

            ScalarOperator arg = call.getChild(0);
            if (call.getFunction() instanceof AggStateIf) {
                // FILTER and the _if combinator share a nullable input for both the sum and the count.
                // Deduplication for DISTINCT must also operate on this filtered value.
                arg = new CaseWhenOperator(arg.getType(), null, null, List.of(call.getChild(1), arg));
            }
            int scale = ((ScalarType) arg.getType()).getScalarScale();
            ScalarType wide = wideType(scale);
            ColumnRefOperator input = wideInputs.computeIfAbsent(arg, key -> {
                CastOperator cast = new CastOperator(wide, key);
                ColumnRefOperator column = factory.create(cast, wide, cast.isNullable());
                inputs.put(column, cast);
                return column;
            });
            CallOperator sumCall = aggregateCall(FunctionSet.SUM, input, wide, call.isDistinct());
            ScalarType publicType = (ScalarType) result.getType();
            boolean average = FunctionSet.AVG.equals(aggregateName(call));
            ScalarOperator sum;
            ScalarOperator count = null;
            if (call.isDistinct() && operator instanceof LogicalWindowOperator) {
                // The stock window aggregate deduplicates once and exposes both fields. Separate
                // DISTINCT sum and count can lose deduplication in the window-to-join rewrite.
                ColumnRefOperator state = distinctWindowStates.computeIfAbsent(input, key -> {
                    CallOperator fused = ScalarOperatorUtil.buildFusedMultiDistinct(List.of(sumCall), key);
                    return addAggregate(fused, aggregates, factory);
                });
                sum = new SubfieldOperator(state, wide, List.of(FunctionSet.SUM));
                count = new SubfieldOperator(state, Type.BIGINT, List.of(FunctionSet.COUNT));
            } else {
                sum = addAggregate(sumCall, aggregates, factory);
                if (average) {
                    count = addAggregate(aggregateCall(FunctionSet.COUNT, input, Type.BIGINT, call.isDistinct()),
                            aggregates, factory);
                }
            }
            ScalarOperator value;
            if (average) {
                value = finalizeAverage(sum, count, wide, publicType);
            } else {
                ScalarOperator empty = new IsNullPredicateOperator(sum);
                ScalarOperator safeSum = onEmpty(empty, ConstantOperator.createDecimal(BigDecimal.ZERO, wide), sum);
                value = onEmpty(empty, ConstantOperator.createNull(publicType), checkedNarrow(safeSum, publicType));
            }
            outputs.put(result, value);
        }

        OptExprBuilder projected = child.withNewRoot(new LogicalProjectOperator(inputs));
        LogicalOperator widened;
        if (operator instanceof LogicalAggregationOperator aggregation) {
            widened = LogicalAggregationOperator.builder().withOperator(aggregation).setAggregations(aggregates).build();
        } else {
            LogicalWindowOperator window = (LogicalWindowOperator) operator;
            Map<ColumnRefOperator, CallOperator> windowCalls = aggregates;
            if (!distinctWindowStates.isEmpty() && aggregates.values().stream().anyMatch(CallOperator::isDistinct)) {
                // Keep unrelated DISTINCT calls eligible for their normal window-to-join rewrite.
                // The fused wide state is a window function and cannot be executed as a grouped aggregate.
                windowCalls = new LinkedHashMap<>();
                for (ColumnRefOperator state : distinctWindowStates.values()) {
                    windowCalls.put(state, aggregates.remove(state));
                }
                projected = projected.withNewRoot(
                        LogicalWindowOperator.builder().withOperator(window).setWindowCall(aggregates).build());
            }
            widened = LogicalWindowOperator.builder().withOperator(window).setWindowCall(windowCalls).build();
        }
        return new OptExprBuilder(widened, List.of(projected), outputMapping)
                .withNewRoot(new LogicalProjectOperator(outputs));
    }

    private static ScalarOperator onEmpty(ScalarOperator empty, ScalarOperator whenEmpty, ScalarOperator otherwise) {
        return new CaseWhenOperator(otherwise.getType(), null, otherwise, List.of(empty, whenEmpty));
    }

    private static ScalarOperator finalizeAverage(ScalarOperator sum, ScalarOperator count,
                                                  ScalarType inputType, ScalarType publicType) {
        // A fused DISTINCT window can publish a NULL count along with its NULL sum.
        ScalarOperator empty = new IsNullPredicateOperator(sum);
        // Use defined inputs even in empty groups or frames. Stock vectorized operators can evaluate
        // an unused CASE arm and can inspect the bytes behind an aggregate's NULL.
        ScalarOperator zero = ConstantOperator.createDecimal(BigDecimal.ZERO, inputType);
        ScalarOperator numerator = onEmpty(empty, zero, sum);
        ScalarOperator divisor = new CastOperator(wideType(0), onEmpty(empty, ConstantOperator.createBigint(1), count));
        int inputScale = inputType.getScalarScale();
        int outputScale = publicType.getScalarScale();
        if (inputScale > outputScale) {
            // Round the rational sum/count once at the public scale. Rounding the wide AVG and then
            // casting would incorrectly round AVG(4e-19, 5e-19) to 1e-18 at scale 18.
            ScalarOperator unit = ConstantOperator.createDecimal(BigDecimal.ONE.scaleByPowerOfTen(-outputScale),
                    wideType(outputScale));
            ScalarOperator quantum = new CastOperator(inputType,
                    arithmetic(FunctionSet.MULTIPLY, wideType(outputScale), divisor, unit));
            ScalarOperator remainder = arithmetic(FunctionSet.MOD, inputType, numerator, quantum);
            ScalarOperator twice = arithmetic(FunctionSet.ADD, inputType, remainder, remainder);
            ScalarOperator negQuantum = arithmetic(FunctionSet.SUBTRACT, inputType, zero, quantum);
            ScalarOperator delta = new CaseWhenOperator(inputType, null, zero, List.of(
                    new BinaryPredicateOperator(BinaryType.GE, twice, quantum), quantum,
                    new BinaryPredicateOperator(BinaryType.LE, twice, negQuantum), negQuantum));
            numerator = arithmetic(FunctionSet.ADD, inputType,
                    arithmetic(FunctionSet.SUBTRACT, inputType, numerator, remainder), delta);
        }
        // For high-scale inputs the corrected numerator is an exact multiple of count * 10^-outputScale.
        ScalarOperator average = arithmetic(FunctionSet.DIVIDE, wideType(Math.max(inputScale, outputScale)),
                numerator, divisor);
        return onEmpty(empty, ConstantOperator.createNull(publicType), checkedNarrow(average, publicType));
    }

    private static String aggregateName(CallOperator call) {
        return call.getFunction() instanceof AggStateIf filtered ? filtered.getAggStateDesc().getFunctionName()
                : call.getFnName();
    }

    private static boolean needsWideSum(CallOperator call) {
        String name = aggregateName(call);
        return (FunctionSet.SUM.equals(name) || FunctionSet.AVG.equals(name)) &&
                !call.getFunction().isUdf() && call.getType().getPrimitiveType() == PrimitiveType.DECIMAL128 &&
                call.getChild(0).getType().isDecimalV3() && !call.getChild(0).getType().isDecimal256();
    }

    private static CallOperator aggregateCall(String name, ColumnRefOperator input, Type resultType, boolean distinct) {
        Type argType = input.getType();
        AggregateFunction builtin = (AggregateFunction) Expr.getBuiltinFunction(name, new Type[] {argType},
                Function.CompareMode.IS_NONSTRICT_SUPERTYPE_OF);
        AggregateFunction function = builtin.withNewTypes(List.of(argType), resultType);
        return new CallOperator(name, resultType, List.of(input), function, distinct);
    }

    private static ColumnRefOperator addAggregate(CallOperator call, Map<ColumnRefOperator, CallOperator> aggregates,
                                                  ColumnRefFactory factory) {
        ColumnRefOperator column = factory.create(call.getFnName(), call.getType(),
                !FunctionSet.COUNT.equals(call.getFnName()));
        aggregates.put(column, call);
        return column;
    }
}
