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

package software.amazon.smithy.lsp.ext;

import java.util.Arrays;
import java.util.List;

public final class Constants {
    public static final String SMITHY_EXTENSION = ".smithy";

    public static final List<String> BUILD_FILES = Arrays.asList("build/smithy-dependencies.json", ".smithy.json",
            "smithy-build.json");

    public static final List<String> KEYWORDS = Arrays.asList("bigDecimal", "bigInteger", "blob", "boolean", "byte",
            "create", "collectionOperations", "delete", "document", "double", "errors", "float", "identifiers", "input",
            "integer", "integer", "key", "list", "long", "map", "member", "metadata", "namespace", "operation",
            "operations",
            "output", "put", "read", "rename", "resource", "resources", "service", "set", "short", "string",
            "structure",
            "timestamp", "union", "update", "use", "value", "version");

    public static final String SMITHY_PRELUDE_NAMESPACE = "smithy.api";

    private Constants() {
    }

}
