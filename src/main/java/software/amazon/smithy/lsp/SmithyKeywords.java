/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.util.Arrays;
import java.util.List;

public final class SmithyKeywords {
  public static final List<String> BUILT_IN_TYPES = Arrays.asList("Blob", "Boolean", "String", "Byte", "Short",
      "Integer", "Long", "Float", "Double", "BigInteger", "BigDecimal", "Timestamp", "Document");
  public static final List<String> KEYWORDS = Arrays.asList("bigDecimal", "bigInteger", "blob", "boolean", "byte",
          "create", "collectionOperations", "delete", "document", "double", "errors", "float", "identifiers", "input",
          "integer", "integer", "key", "list", "long", "map", "member", "metadata", "namespace", "operation",
          "operations", "output", "put", "read", "resource", "resources", "service", "set", "short", "string",
          "structure", "timestamp", "union", "update", "use", "value", "version");

  private SmithyKeywords() {

  }
}
