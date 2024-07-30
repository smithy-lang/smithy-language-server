/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp;

import org.eclipse.lsp4j.jsonrpc.util.Preconditions;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;

public class SelectorParams {
    @NonNull
    private final String expression;

    public SelectorParams(@NonNull final String selector) {
        this.expression = Preconditions.checkNotNull(selector, "selector");
    }

    public String getExpression() {
        return this.expression;
    }
}
