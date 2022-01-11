package software.amazon.smithy.lsp.ext;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class DocumentPreamble {
  private Range namespace;
  private Range useBlock;

  public DocumentPreamble(Range namespace, Range useBlock) {
    this.namespace = namespace;
    this.useBlock = useBlock;
  }

  public Range getUseBlockRange() {
    return useBlock;
  }

  public Range getNamespaceRange() {
    return namespace;
  }

  @Override
  public String toString() {
    return "DocumentPreamble(namespace=" + namespace + ", useBlock=" + useBlock + ")";
  }
}
