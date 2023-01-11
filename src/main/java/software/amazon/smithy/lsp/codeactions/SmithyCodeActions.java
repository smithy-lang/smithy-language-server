/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.codeactions;

import java.util.Arrays;
import java.util.List;

public final class SmithyCodeActions {
    public static final String SMITHY_UPDATE_VERSION = "smithyUpdateVersion";
    public static final String SMITHY_DEFINE_VERSION = "smithyDefineVersion";

    private SmithyCodeActions() {

    }

    public static List<String> all() {
        return Arrays.asList(SMITHY_UPDATE_VERSION, SMITHY_DEFINE_VERSION);
    }
}