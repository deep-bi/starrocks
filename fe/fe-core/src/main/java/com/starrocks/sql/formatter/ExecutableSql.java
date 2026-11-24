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

package com.starrocks.sql.formatter;

import com.starrocks.sql.ast.ParseNode;
import com.starrocks.sql.ast.expression.ArithmeticExpr;

public final class ExecutableSql {
    // Nested expression and subquery printers create their own visitors. They must all
    // preserve the parser's unary-minus marker when the emitted SQL will be parsed again.
    private static final ThreadLocal<AST2SQLVisitor> CURRENT = new ThreadLocal<>();

    private ExecutableSql() {}

    public static String formatPreservingUnaryMinus(ParseNode node, boolean hideCredential) {
        AST2SQLVisitor visitor = AST2SQLVisitor.withOptions(FormatOptions.allEnable()
                .setColumnSimplifyTableName(false).setEnableDigest(false).setHideCredential(hideCredential));
        AST2SQLVisitor previous = CURRENT.get();
        CURRENT.set(visitor);
        try {
            return visitor.visit(node);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    static String formatUnaryMinus(ArithmeticExpr expr) {
        AST2SQLVisitor visitor = expr.isUnaryMinus() ? CURRENT.get() : null;
        return visitor == null ? null : "-(" + visitor.visit(expr.getChild(1)) + ")";
    }
}
