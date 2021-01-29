package software.amazon.smithy.lsp;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;

/**
 * Interface for protocol extensions for Smithy
 */
@JsonSegment("smithy")
public interface SmithyProtocolExtensions {

  @JsonRequest
  CompletableFuture<String> jarFileContents(TextDocumentIdentifier documentUri);

}
