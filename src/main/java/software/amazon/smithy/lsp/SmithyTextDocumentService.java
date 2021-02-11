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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public class SmithyTextDocumentService implements TextDocumentService {

  private List<CompletionItem> baseCompletions = new ArrayList<CompletionItem>();
  private Optional<LanguageClient> client = Optional.empty();
  private Map<String, List<? extends Location>> locations = new HashMap<String, List<? extends Location>>();
  private List<? extends Location> noLocations = Arrays.asList();

  /**
   * @param client Language Client to be used by text document service.
   */
  public SmithyTextDocumentService(Optional<LanguageClient> client) {
    this.client = client;

    List<CompletionItem> keywordCompletions = SmithyKeywords.KEYWORDS.stream()
        .map(kw -> create(kw, CompletionItemKind.Keyword)).collect(Collectors.toList());

    List<CompletionItem> baseTypesCompletions = SmithyKeywords.BUILT_IN_TYPES.stream()
        .map(kw -> create(kw, CompletionItemKind.Class)).collect(Collectors.toList());

    baseCompletions.addAll(keywordCompletions);
    baseCompletions.addAll(baseTypesCompletions);
  }

  public void setClient(LanguageClient client) {
    this.client = Optional.of(client);
  }

  private MessageParams msg(final MessageType sev, final String cont) {
    return new MessageParams(sev, cont);
  }

  @Override
  public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
    return Utils.completableFuture(Either.forLeft(baseCompletions));
  }

  @Override
  public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
    return Utils.completableFuture(unresolved);
  }

  private CompletionItem create(String s, CompletionItemKind kind) {
    CompletionItem ci = new CompletionItem(s);
    ci.setKind(kind);
    return ci;
  }

  private List<String> readAll(File f) throws IOException {
    return Files.readAllLines(f.toPath());
  }

  private String findToken(String path, Position p) throws IOException {
    List<String> contents;
    if (Utils.isSmithyJarFile(path)) {
      contents = Utils.jarFileContents(path, this.getClass().getClassLoader());
    } else {
      contents = readAll(new File(URI.create(path)));
    }

    String line = contents.get(p.getLine());
    Integer col = p.getCharacter();

    String before = line.substring(0, col);
    String after = line.substring(col, line.length());

    StringBuilder beforeAcc = new StringBuilder();
    StringBuilder afterAcc = new StringBuilder();

    int idx = 0;

    while (idx < after.length()) {
      if (Character.isLetterOrDigit(after.charAt(idx))) {
        afterAcc.append(after.charAt(idx));
        idx = idx + 1;
      } else {
        idx = after.length();
      }
    }

    idx = before.length() - 1;

    while (idx > 0) {
      char c = before.charAt(idx);
      if (Character.isLetterOrDigit(c)) {
        beforeAcc.append(c);
        idx = idx - 1;
      } else {
        idx = 0;
      }
    }

    return beforeAcc.reverse().append(afterAcc).toString();
  }

  @Override
  public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
      DefinitionParams params) {
    try {
      String found = findToken(params.getTextDocument().getUri(), params.getPosition());
      return Utils.completableFuture(Either.forLeft(locations.getOrDefault(found, noLocations)));
    } catch (Exception e) {
      // TODO: handle exception

      e.printStackTrace();

      return Utils.completableFuture(Either.forLeft(noLocations));
    }
  }

  @Override
  public void didChange(DidChangeTextDocumentParams params) {
    File tempFile = null;

    try {
      tempFile = File.createTempFile("smithy", ".smithy");

      Files.write(tempFile.toPath(), params.getContentChanges().get(0).getText().getBytes());

    } catch (Exception e) {

    }

    recompile(tempFile, Optional.of(fileUri(params.getTextDocument())));
  }

  @Override
  public void didOpen(DidOpenTextDocumentParams params) {
    String rawUri = params.getTextDocument().getUri();
    if (Utils.isFile(rawUri)) {
      recompile(fileUri(params.getTextDocument()), Optional.empty());
    }
  }

  @Override
  public void didClose(DidCloseTextDocumentParams params) {
    recompile(fileUri(params.getTextDocument()), Optional.empty());
  }

  @Override
  public void didSave(DidSaveTextDocumentParams params) {
    recompile(fileUri(params.getTextDocument()), Optional.empty());
  }

  private File fileUri(TextDocumentIdentifier tdi) {
    return new File(URI.create(tdi.getUri()));
  }

  private File fileUri(TextDocumentItem tdi) {
    return new File(URI.create(tdi.getUri()));
  }

  /**
   * @param path     Path of new model file.
   * @param original Original model file to compare against when recompiling.
   */
  public void recompile(File path, Optional<File> original) {
    Either<Exception, ValidatedResult<Model>> loadedModel = SmithyInterface.readModel(path);

    String changedFileUri = original.map(f -> f.getAbsolutePath()).orElse(path.getAbsolutePath());

    client.ifPresent(cl -> {
      if (loadedModel.isLeft()) {
        cl.showMessage(msg(MessageType.Error, changedFileUri + " is not okay!" + loadedModel.getLeft().toString()));
      } else {
        ValidatedResult<Model> result = loadedModel.getRight();

        if (result.isBroken()) {
          List<ValidationEvent> events = result.getValidationEvents();

          List<Diagnostic> msgs = events.stream().map(ev -> ProtocolAdapter.toDiagnostic(ev))
              .collect(Collectors.toList());

          PublishDiagnosticsParams diagnostics = createDiagnostics(changedFileUri, msgs);

          cl.publishDiagnostics(diagnostics);
        } else {
          if (!original.isPresent()) {
            result.getResult().ifPresent(m -> updateLocations(m));
          }
          cl.publishDiagnostics(createDiagnostics(changedFileUri, Arrays.asList()));
        }
      }
    });
  }

  /**
   *
   * @param model Model to get source locations of shapes.
   */
  public void updateLocations(Model model) {
    model.shapes().forEach(shape -> {
      SourceLocation sourceLocation = shape.getSourceLocation();
      String uri = sourceLocation.getFilename();
      if (uri.startsWith("jar:file:")) {
        uri = "smithyjar:" + uri.substring(9);
      } else if (!uri.startsWith("file:")) {
        uri = "file:" + uri;
      }
      Position pos = new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
      Location location = new Location(uri, new Range(pos, pos));

      locations.put(shape.getId().getName(), Arrays.asList(location));
    });
  }

  private PublishDiagnosticsParams createDiagnostics(String uri, final List<Diagnostic> diagnostics) {
    return new PublishDiagnosticsParams(uri, diagnostics);
  }

}
