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
import java.util.Collections;
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
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;

import software.amazon.smithy.lsp.ext.Constants;
import software.amazon.smithy.lsp.ext.LspLog;
import software.amazon.smithy.lsp.ext.SmithyBuildExtensions;
import software.amazon.smithy.lsp.ext.SmithyProject;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public class SmithyTextDocumentService implements TextDocumentService {

    private final List<CompletionItem> baseCompletions = new ArrayList<CompletionItem>();
    private Optional<LanguageClient> client = Optional.empty();
    private final List<Location> noLocations = Collections.emptyList();
    private SmithyProject project;

    // when files are edited, their contents will be persisted in memory and removed
    // on didSave or didClose
    private Map<File, String> temporaryContents = new HashMap();

    /**
     * @param client Language Client to be used by text document service.
     */
    public SmithyTextDocumentService(Optional<LanguageClient> client) {
        this.client = client;
    }

    public void setClient(LanguageClient client) {
        this.client = Optional.of(client);
    }

    /**
     * Processes extensions.
     * <p>
     * 1. Downloads external dependencies as jars 2. Creates a model from just
     * external jars 3. Updates locations index with symbols found in external jars
     *
     * @param ext extensions
     */
    public void createProject(SmithyBuildExtensions ext, File root) {
        Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, root);
        if (loaded.isRight()) {
            this.project = loaded.getRight();
            sendInfo("Project loaded with " + this.project.getExternalJars().size() + " external jars and "
                    + this.project.getSmithyFiles().size() + " discovered smithy files");
        } else {
            sendError("Failed to create Smithy project: " + loaded.getLeft().toString());
        }
    }

    private MessageParams msg(final MessageType sev, final String cont) {
        return new MessageParams(sev, cont);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        LspLog.println("Asking to complete " + position + " in class " + position.getTextDocument().getClass());

        try {
            String found = findToken(position.getTextDocument().getUri(), position.getPosition());
            LspLog.println("Token for completion: " + found + " in class " + position.getTextDocument().getClass());
            return Utils.completableFuture(Either.forLeft(project.getCompletions(found)));
        } catch (Exception e) {
            LspLog.println(
                    "Failed to identify token for completion in " + position.getTextDocument().getUri() + ": " + e);
        }
        return Utils.completableFuture(Either.forLeft(baseCompletions));
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        return Utils.completableFuture(unresolved);
    }

    private List<String> readAll(File f) throws IOException {
        return Files.readAllLines(f.toPath());
    }

    private String findToken(String path, Position p) throws IOException {
        List<String> contents;
        if (Utils.isSmithyJarFile(path)) {
            contents = Utils.jarFileContents(path);
        } else {
            String tempContents = temporaryContents.get(fileFromUri(path));
            if (tempContents != null) {
                LspLog.println("Path " + path + " was found in temporary buffer");
                contents = Arrays.stream(tempContents.split("\n")).collect(Collectors.toList());
            } else {
                contents = readAll(new File(URI.create(path)));
            }

        }

        String line = contents.get(p.getLine());
        int col = p.getCharacter();

        LspLog.println("Trying to find a token in line '" + line + "' at position " + p);

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
            return Utils.completableFuture(Either.forLeft(project.getLocations().getOrDefault(found, noLocations)));
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
            if (params.getContentChanges().size() > 0) {
                tempFile = File.createTempFile("smithy", Constants.SMITHY_EXTENSION);

                String contents = params.getContentChanges().get(0).getText();

                unstableContents(fileUri(params.getTextDocument()), contents);

                Files.write(tempFile.toPath(), contents.getBytes());
            }

        } catch (Exception ignored) {

        }

        report(recompile(fileUri(params.getTextDocument()), Optional.ofNullable(tempFile)));
    }

    private void stableContents(File file) {
        this.temporaryContents.remove(file);
    }

    private void unstableContents(File file, String contents) {
        this.temporaryContents.put(file, contents);
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String rawUri = params.getTextDocument().getUri();
        if (Utils.isFile(rawUri)) {
            report(recompile(fileUri(params.getTextDocument()), Optional.empty()));
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        File file = fileUri(params.getTextDocument());
        stableContents(file);
        report(recompile(file, Optional.empty()));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        File file = fileUri(params.getTextDocument());
        stableContents(file);
        report(recompile(file, Optional.empty()));
    }

    private File fileUri(TextDocumentIdentifier tdi) {
        return fileFromUri(tdi.getUri());
    }

    private File fileUri(TextDocumentItem tdi) {
        return fileFromUri(tdi.getUri());
    }

    private File fileFromUri(String uri) {
        return new File(URI.create(uri));
    }

    /**
     * @param result Either a fatal error message, or a list of diagnostics to
     *               publish
     */
    public void report(Either<String, List<PublishDiagnosticsParams>> result) {
        client.ifPresent(cl -> {

            if (result.isLeft()) {
                cl.showMessage(msg(MessageType.Error, result.getLeft()));
            } else {
                result.getRight().forEach(cl::publishDiagnostics);
            }
        });
    }

    /**
     * Breaks down a list of validation events into a per-file list of diagnostics,
     * explicitly publishing an empty list of diagnostics for files not present in
     * validation events.
     *
     * @param events   output of the Smithy model builder
     * @param allFiles all the files registered for the project
     * @return a list of LSP diagnostics to publish
     */
    public List<PublishDiagnosticsParams> createPerFileDiagnostics(List<ValidationEvent> events, List<File> allFiles) {
        Map<File, List<ValidationEvent>> byFile = new HashMap<File, List<ValidationEvent>>();

        for (ValidationEvent ev : events) {
            File file = new File(ev.getSourceLocation().getFilename());
            if (byFile.containsKey(file)) {
                byFile.get(file).add(ev);
            } else {
                List<ValidationEvent> l = new ArrayList<ValidationEvent>();
                l.add(ev);
                byFile.put(file, l);
            }
        }

        allFiles.forEach(f -> {
            if (!byFile.containsKey(f)) {
                byFile.put(f, Collections.emptyList());
            }
        });

        List<PublishDiagnosticsParams> diagnostics = new ArrayList<PublishDiagnosticsParams>();

        byFile.entrySet().forEach(e -> {
            diagnostics.add(createDiagnostics(e.getKey().toURI().toString(),
                    e.getValue().stream().map(ProtocolAdapter::toDiagnostic).collect(Collectors.toList())));
        });

        return diagnostics;

    }

    /**
     * Main recompilation method, responsible for reloading the model, persisting it
     * if necessary, and massaging validation events into publishable diagnostics.
     *
     * @param path      file that triggered recompilation
     * @param temporary optional location of a temporary file with most recent
     *                  contents
     * @return either a fatal error message, or a list of diagnostics
     */
    public Either<String, List<PublishDiagnosticsParams>> recompile(File path, Optional<File> temporary) {
        File latestContents = temporary.orElse(path);
        Either<Exception, SmithyProject> loadedModel = this.project.recompile(latestContents);

        if (loadedModel.isLeft()) {
            return Either.forLeft(path + " is not okay!" + loadedModel.getLeft().toString());
        } else {
            ValidatedResult<Model> result = loadedModel.getRight().getModel();
            // If we're working with a temporary file, we don't want to persist the result
            // of the project
            if (!temporary.isPresent()) {
                this.project = loadedModel.getRight();
            }

            List<ValidationEvent> events = new ArrayList<ValidationEvent>();
            List<File> allFiles;

            if (temporary.isPresent()) {
                allFiles = project.getSmithyFiles().stream().filter(f -> !f.equals(temporary.get()))
                        .collect(Collectors.toList());
                // We need to remap some validation events
                // from temporary files to the one on which didChange was invoked
                for (ValidationEvent ev : result.getValidationEvents()) {
                    if (ev.getSourceLocation().getFilename().equals(temporary.get().getAbsolutePath())) {
                        SourceLocation sl = new SourceLocation(path.getAbsolutePath(), ev.getSourceLocation().getLine(),
                                ev.getSourceLocation().getColumn());
                        ValidationEvent newEvent = ev.toBuilder().sourceLocation(sl).build();

                        events.add(newEvent);
                    } else {
                        events.add(ev);
                    }
                }
            } else {
                events.addAll(result.getValidationEvents());
                allFiles = project.getSmithyFiles();
            }

            LspLog.println(
                    "Recompiling " + path + " (with temporary content " + temporary + ") raised diagnostics:" + events);
            return Either.forRight(createPerFileDiagnostics(events, allFiles));
        }

    }

    private void sendInfo(String msg) {
        this.client.ifPresent(client -> {
            client.showMessage(new MessageParams(MessageType.Info, msg));
        });
    }

    private void sendError(String msg) {
        this.client.ifPresent(client -> {
            client.showMessage(new MessageParams(MessageType.Error, msg));
        });
    }

    private PublishDiagnosticsParams createDiagnostics(String uri, final List<Diagnostic> diagnostics) {
        if (!uri.startsWith("file:")) {
            uri = "file:" + uri;
        }
        return new PublishDiagnosticsParams(uri, diagnostics);
    }

}
