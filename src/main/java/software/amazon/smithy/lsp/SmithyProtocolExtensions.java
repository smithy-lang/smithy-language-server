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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;
import software.amazon.smithy.lsp.ext.serverstatus.ServerStatus;

/**
 * Interface for protocol extensions for Smithy.
 */
@JsonSegment("smithy")
public interface SmithyProtocolExtensions {

  @JsonRequest
  CompletableFuture<String> jarFileContents(TextDocumentIdentifier documentUri);

  @JsonRequest
  CompletableFuture<List<? extends Location>> selectorCommand(SelectorParams selectorParams);

  /**
   * Get a snapshot of the server's status, useful for debugging purposes.
   *
   * @return A future containing the server's status
   */
  @JsonRequest
  CompletableFuture<ServerStatus> serverStatus();
}
