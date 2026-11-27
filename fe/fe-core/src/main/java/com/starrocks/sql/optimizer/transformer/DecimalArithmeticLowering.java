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

import com.starrocks.analysis.ArithmeticExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.ScalarFunction;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SqlModeHelper;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A typed operation retained through FE rewrites and expanded into ordinary stock casts and
 * decimal arithmetic when building the executable expression. No private function is sent to a BE.
 */
public final class DecimalArithmeticLowering extends ScalarFunction {
    private final String operation;

    private DecimalArithmeticLowering(String operation, Type[] args, Type result) {
        super(new FunctionName("__checked_decimal_" + operation), args, result, false);
        this.operation = operation;
    }

    private DecimalArithmeticLowering(DecimalArithmeticLowering other) {
        super(other);
        operation = other.operation;
    }

    @Override
    public Function copy() {
        return new DecimalArithmeticLowering(this);
    }

    public static boolean isEligible(ArithmeticExpr expr, Function resolved) {
        if (!expr.allowsDecimalScaleReduction() || !expr.getType().isDecimalV3() || expr.getType().isDecimal256()) {
            return false;
        }
        return switch (expr.getOp()) {
            case ADD, SUBTRACT -> ArithmeticExpr.canOverflowAddSub(expr);
            case DIVIDE -> true;
            // An exact product already fits in its declared precision.
            case MULTIPLY -> resolved.getArgs()[0].getPrecision() + resolved.getArgs()[1].getPrecision() > 38;
            default -> false;
        };
    }

    static CallOperator binary(ArithmeticExpr expr, Function resolved, ScalarOperator left, ScalarOperator right) {
        String name = expr.getOp().getName();
        if (isEligible(expr, resolved) && ConnectContext.getSessionVariableOrDefault().isDecimalOverflowReduceScale()) {
            resolved = new DecimalArithmeticLowering(name, resolved.getArgs(), expr.getType());
            name = resolved.functionName();
        }
        return new CallOperator(name, expr.getType(), List.of(left, right), resolved);
    }

    static ScalarType wideType(int scale) {
        return ScalarType.createDecimalV3Type(PrimitiveType.DECIMAL256, 76, scale);
    }

    static ScalarOperator arithmetic(String name, ScalarType resultType, ScalarOperator left, ScalarOperator right) {
        Type[] args = {left.getType(), right.getType()};
        Function fn = new ScalarFunction(new FunctionName(name), args, resultType, false);
        return new CallOperator(name, resultType, List.of(left, right), fn);
    }

    static ScalarOperator checkedNarrow(ScalarOperator wide, ScalarType result) {
        Function fn = new DecimalArithmeticLowering("narrow", new Type[] {wide.getType()}, result);
        return new CallOperator(fn.functionName(), result, List.of(wide), fn);
    }

    public ScalarOperator toStockExpression(CallOperator call) {
        ScalarType result = (ScalarType) call.getType();
        ScalarOperator wide = call.getChild(0);
        if (!operation.equals("narrow")) {
            int leftScale = ((ScalarType) getArgs()[0]).getScalarScale();
            int rightScale = ((ScalarType) getArgs()[1]).getScalarScale();
            int scale = operation.equals(FunctionSet.MULTIPLY) ? leftScale + rightScale : result.getScalarScale();
            wide = arithmetic(operation, wideType(scale), new CastOperator(wideType(leftScale), wide),
                    new CastOperator(wideType(rightScale), call.getChild(1)));
        }
        // Round before checking precision so a carry from rounding counts toward the integer digits.
        ScalarType rounded = wideType(result.getScalarScale());
        if (!wide.getType().equals(rounded)) {
            wide = new CastOperator(rounded, wide);
        }
        ScalarOperator value = new CastOperator(Type.VARCHAR, wide);
        int storagePrecision = PrimitiveType.getMaxPrecisionOfDecimal(result.getPrimitiveType());
        if (result.getPrecision() < storagePrecision) {
            // The stock parser checks storage precision. Reserve the unused digits as fractional
            // places so it checks the declared integer range. The final cast removes only zeroes.
            int checkedScale = result.getScalarScale() + (storagePrecision - result.getPrecision());
            ScalarType checked = ScalarType.createDecimalV3Type(result.getPrimitiveType(),
                    storagePrecision, checkedScale);
            value = new CastOperator(checked, value);
        }
        return new CastOperator(result, value);
    }

    public ScalarOperator fold(CallOperator call) {
        ScalarType result = (ScalarType) call.getType();
        if (call.getChildren().stream().anyMatch(arg -> !arg.getType().isDecimalV3())) {
            return call;
        }
        BigDecimal value = ((ConstantOperator) call.getChild(0)).getDecimal();
        int scale = result.getScalarScale();
        if (!operation.equals("narrow")) {
            BigDecimal right = ((ConstantOperator) call.getChild(1)).getDecimal();
            value = switch (operation) {
                case FunctionSet.ADD -> value.add(right);
                case FunctionSet.SUBTRACT -> value.subtract(right);
                case FunctionSet.MULTIPLY -> value.multiply(right);
                case FunctionSet.DIVIDE -> right.signum() == 0 ? null : value.divide(right, scale, RoundingMode.HALF_UP);
                default -> throw new IllegalStateException(operation);
            };
            if (value == null) {
                // Preserve the independent division-by-zero policy on the executable operation.
                return call;
            }
        }
        value = value.setScale(scale, RoundingMode.HALF_UP);
        if (value.precision() > result.getPrecision()) {
            if (SqlModeHelper.check(ConnectContext.getSessionVariableOrDefault().getSqlMode(),
                    SqlModeHelper.MODE_ERROR_IF_OVERFLOW)) {
                // An unreachable branch must not raise a planning-time error.
                return call;
            }
            return ConstantOperator.createNull(result);
        }
        return ConstantOperator.createDecimal(value, result);
    }
}
