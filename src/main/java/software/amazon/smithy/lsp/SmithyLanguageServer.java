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

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

public class SmithyLanguageServer implements LanguageServer, LanguageClientAware, SmithyProtocolExtensions {

  private Optional<LanguageClient> client = Optional.empty();

  private File workspaceRoot = null;

  private SmithyTextDocumentService tds = null;

  @Override
  public CompletableFuture<Object> shutdown() {
    return Utils.completableFuture(new Object());
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    if (params.getRootUri() != null) {
      try {
        workspaceRoot = new File(new URI(params.getRootUri()));
      } catch (Exception e) {
        // TODO: handle exception
      }
    }

    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
    capabilities.setCodeActionProvider(false);
    capabilities.setDefinitionProvider(true);
    capabilities.setDeclarationProvider(true);
    capabilities.setCompletionProvider(new CompletionOptions(true, null));
    capabilities.setHoverProvider(false);

    return Utils.completableFuture(new InitializeResult(capabilities));
  }

  @Override
  public void exit() {
    System.exit(0);

  }

  @Override
  public WorkspaceService getWorkspaceService() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    tds = new SmithyTextDocumentService(this.client);
    return this.tds;
  }

  @Override
  public void connect(LanguageClient client) {

    if (this.tds != null) {
      this.tds.setClient(client);
    }

    client.showMessage(new MessageParams(MessageType.Info, "Hello from smithy-language-server !"));
  }

  @Override
  public CompletableFuture<String> jarFileContents(TextDocumentIdentifier documentUri) {
    try {
      java.util.List<String> lines = Utils.jarFileContents(documentUri.getUri(), this.getClass().getClassLoader());
      String contents = lines.stream().collect(Collectors.joining(System.lineSeparator()));
      return CompletableFuture.completedFuture(contents);
    } catch (IOException e) {
      CompletableFuture<String> future = new CompletableFuture<String>();
      future.completeExceptionally(e);
      return future;
    }
  }

}
