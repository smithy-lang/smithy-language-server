package software.amazon.smithy.lsp;

import java.util.Arrays;
import java.util.List;

public class SmithyKeywords {
  public static final List<String> keywords = Arrays.asList("bigDecimal", "bigInteger", "blob", "boolean", "byte",
      "document", "double", "errors", "float", "input", "integer", "integer", "list", "long", "map", "metadata",
      "namespace", "operation", "output", "resource", "service", "set", "short", "string", "structure", "timestamp",
      "union", "use");

  public static final List<String> builtinTypes = Arrays.asList("Blob", "Boolean", "String", "Byte", "Short", "Integer",
      "Long", "Float", "Double", "BigInteger", "BigDecimal", "Timestamp", "Document");
}
