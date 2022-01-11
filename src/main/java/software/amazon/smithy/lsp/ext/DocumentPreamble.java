package software.amazon.smithy.lsp.ext;

import java.util.Set;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public class DocumentPreamble {
  private Range namespace;
  private Range useBlock;
  private Set<String> imports;
  private boolean blankSeparated;

  public DocumentPreamble(Range namespace, Range useBlock, Set<String> imports, boolean blankSeparated) {
    this.namespace = namespace;
    this.useBlock = useBlock;
    this.imports = imports;
    this.blankSeparated = blankSeparated;
  }

  public Range getUseBlockRange() {
    return useBlock;
  }

  public Range getNamespaceRange() {
    return namespace;
  }

  public boolean hasImport(String i) {
    return imports.contains(i);
  }

  public boolean isBlankSeparated() {
    return this.blankSeparated;
  }

  @Override
  public String toString() {
    return "DocumentPreamble(namespace=" + namespace + ", useBlock=" + useBlock + ")";
  }
}
