package software.amazon.smithy.lsp.ext;

import java.util.Optional;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.xtext.xbase.lib.Pure;

import software.amazon.smithy.utils.Pair;

public class SmithyCompletionItem {
  private CompletionItem ci;
  private Optional<Pair<String, String>> smithyImport = Optional.empty();

  public SmithyCompletionItem(CompletionItem ci) {
    this.ci = ci;
  }

  public SmithyCompletionItem(CompletionItem ci, String namespace, String id) {
    this.ci = ci;
    this.smithyImport = Optional.of(Pair.of(namespace, id));
  }

  public Optional<Pair<String, String>> getImport() {
    return smithyImport;
  }

  public CompletionItem getCompletionItem() {
    return ci;
  }

  public Optional<String> getQualifiedImport() {
    return smithyImport.map(pair -> pair.left + "#" + pair.right);
  }

  public Optional<String> getImportNamespace() {
    return smithyImport.map(f -> f.left);
  }

  @Override
  @Pure
  public boolean equals(final Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    SmithyCompletionItem other = (SmithyCompletionItem) obj;
    if (other.ci != this.ci)
      return false;
    if (other.smithyImport != this.smithyImport)
      return false;
    return true;
  }

  @Override
  @Pure
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((this.ci == null) ? 0 : this.ci.hashCode());
    return prime * result + ((this.smithyImport == null) ? 0 : this.smithyImport.hashCode());
  }
}
