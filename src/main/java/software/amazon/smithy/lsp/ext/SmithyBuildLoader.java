package software.amazon.smithy.lsp.ext;

import java.nio.file.Path;
import java.nio.file.Paths;

import software.amazon.smithy.model.loader.ModelSyntaxException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.utils.IoUtils;

public final class SmithyBuildLoader {

  public static SmithyBuildExtensions load(Path path) throws ValidationException {
    try {
      String content = IoUtils.readUtf8File(path);
      Path baseImportPath = path.getParent();
      if (baseImportPath == null) {
        baseImportPath = Paths.get(".");
      }
      return load(baseImportPath, loadWithJson(path, content).expectObjectNode());
    } catch (ModelSyntaxException e) {
      throw new ValidationException(e.toString());
    }
  }

  static SmithyBuildExtensions load(Path path, String content) throws ValidationException {
    try {
      Path baseImportPath = path.getParent();
      if (baseImportPath == null) {
        baseImportPath = Paths.get(".");
      }
      return load(baseImportPath, loadWithJson(path, content).expectObjectNode());
    } catch (ModelSyntaxException e) {
      throw new ValidationException(e.toString());
    }
  }

  private static Node loadWithJson(Path path, String contents) {
    return Node.parseJsonWithComments(contents, path.toString());
  }

  private static SmithyBuildExtensions load(Path baseImportPath, ObjectNode node) {
    NodeMapper mapper = new NodeMapper();
    SmithyBuildExtensions config = mapper.deserialize(node, SmithyBuildExtensions.class);
    return config;
  }

}
