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

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
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
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.util.Positions;

import software.amazon.smithy.lsp.ext.Constants;
import software.amazon.smithy.lsp.ext.DocumentPreamble;
import software.amazon.smithy.lsp.ext.LspLog;
import software.amazon.smithy.lsp.ext.SmithyBuildExtensions;
import software.amazon.smithy.lsp.ext.SmithyBuildLoader;
import software.amazon.smithy.lsp.ext.SmithyCompletionItem;
import software.amazon.smithy.lsp.ext.SmithyProject;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.ListUtils;

public class SmithyTextDocumentService implements TextDocumentService {

    private final List<CompletionItem> baseCompletions = new ArrayList<CompletionItem>();
    private Optional<LanguageClient> client = Optional.empty();
    private final List<Location> noLocations = Collections.emptyList();
    private SmithyProject project;
    private File temporaryFolder;

    // when files are edited, their contents will be persisted in memory and removed
    // on didSave or didClose
    private Map<File, String> temporaryContents = new ConcurrentHashMap<>();

    // We use this function to has filepaths to the same location in temporary
    // folder
    private HashFunction hash = Hashing.murmur3_128();

    /**
     * @param client Language Client to be used by text document service.
     */
    public SmithyTextDocumentService(Optional<LanguageClient> client, File tempFile) {
        this.client = client;
        this.temporaryFolder = tempFile;
    }

    public void setClient(LanguageClient client) {
        this.client = Optional.of(client);
    }

    public Optional<File> getRoot() {
        return Optional.ofNullable(project).map(proj -> proj.getRoot());
    }

    /**
     * Processes extensions.
     * <p>
     * 1. Downloads external dependencies as jars 2. Creates a model from just
     * external jars 3. Updates locations index with symbols found in external jars
     *
     * @param ext  extensions
     * @param root workspace root
     */
    public void createProject(SmithyBuildExtensions ext, File root) {
        Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, root);
        if (loaded.isRight()) {
            this.project = loaded.getRight();
            clearAllDiagnostics();
            sendInfo("Project loaded with " + this.project.getExternalJars().size() + " external jars and "
                    + this.project.getSmithyFiles().size() + " discovered smithy files");
        } else {
            sendError("Failed to create Smithy project: " + loaded.getLeft().toString());
        }
    }

    /**
     * Discovers Smithy build files and loads the smithy project defined by them.
     *
     * @param root workspace root
     */
    public void createProject(File root) {
        LspLog.println("Recreating project from " + root);
        SmithyBuildExtensions.Builder result = SmithyBuildExtensions.builder();
        List<String> brokenFiles = new ArrayList<String>();

        for (String file : Constants.BUILD_FILES) {
            File smithyBuild = Paths.get(root.getAbsolutePath(), file).toFile();
            if (smithyBuild.isFile()) {
                try {
                    SmithyBuildExtensions local = SmithyBuildLoader.load(smithyBuild.toPath());
                    result.merge(local);
                    LspLog.println("Loaded build extensions " + local + " from " + smithyBuild.getAbsolutePath());
                } catch (Exception e) {
                    LspLog.println("Failed to load config from" + smithyBuild + ": " + e.toString());
                    brokenFiles.add(smithyBuild.toString());
                }
            }
        }

        if (brokenFiles.isEmpty()) {
            createProject(result.build(), root);
        } else {
            sendError("Failed to load the build, following files have problems: \n" + String.join("\n", brokenFiles));

        }
    }

    private MessageParams msg(final MessageType sev, final String cont) {
        return new MessageParams(sev, cont);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams position) {
        LspLog.println("Asking to complete " + position + " in class " + position.getTextDocument().getClass());

        try {
            String documentUri = position.getTextDocument().getUri();
            String found = findToken(documentUri, position.getPosition());
            DocumentPreamble preamble = documentPreamble(textBufferContents(documentUri));
            LspLog.println("Import statements will be inserted at: " + preamble);
            LspLog.println("Token for completion: " + found + " in class " + position.getTextDocument().getClass());

            List<CompletionItem> items = project.getCompletions(found).stream().map(sci -> {
                CompletionItem result = sci.getCompletionItem();
                Optional<String> qualifiedImport = sci.getQualifiedImport();
                Optional<String> importNamespace = sci.getImportNamespace();
                if (!qualifiedImport.isEmpty() && !preamble.hasImport(qualifiedImport.get())
                        && !importNamespace.equals(Optional.of(Constants.SMITHY_PRELUDE_NAMESPACE))) {
                    TextEdit te = insertLine("use " + qualifiedImport.get(), preamble);
                    result.setAdditionalTextEdits(ListUtils.of(te));
                }

                return result;
            }).collect(Collectors.toList());

            LspLog.println("Completion items: " + items);

            return Utils.completableFuture(Either.forLeft(items));
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

    private File designatedTemporaryFile(File source) {
        String hashed = hash.hashString(source.getAbsolutePath(), StandardCharsets.UTF_8).toString();

        return new File(this.temporaryFolder, hashed + Constants.SMITHY_EXTENSION);
    }

    private List<String> textBufferContents(String path) throws IOException {
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

        return contents;
    }

    private Position blankPosition = new Position(-1, 0);
    private Position documentStart = new Position(0, 0);

    private TextEdit insertLine(String line, DocumentPreamble preamble) {
        String trailingNewLine;
        if (!preamble.isBlankSeparated())
            trailingNewLine = "\n";
        else
            trailingNewLine = "";
        // case 1 - there's no use block at all, so we need to insert the line directly
        // under namespace
        if (preamble.getUseBlockRange().getStart() == blankPosition) {
            // case 1.a - there's no namespace - that means the document is invalid
            // so we'll just insert the line at the beginning of the document
            if (preamble.getNamespaceRange().getStart() == blankPosition) {
                return new TextEdit(new Range(documentStart, documentStart), line + trailingNewLine);
            } else {
                Position namespaceEnd = preamble.getNamespaceRange().getEnd();
                namespaceEnd.setCharacter(namespaceEnd.getCharacter() + 1);
                return new TextEdit(new Range(namespaceEnd, namespaceEnd), "\n" + line + trailingNewLine);
            }
        } else {
            Position useBlockEnd = preamble.getUseBlockRange().getEnd();
            return new TextEdit(new Range(useBlockEnd, useBlockEnd), "\n" + line + trailingNewLine);
        }
    }

    private DocumentPreamble documentPreamble(List<String> lines) {

        Range namespace = new Range(blankPosition, blankPosition);
        Range useBlock = new Range(blankPosition, blankPosition);
        Set<String> imports = new HashSet();

        // First, we detect the namespace and use block in a very ugly way
        boolean collectUseBlock = true;
        int lineNumber = 0;
        for (String line : lines) {
            if (line.trim().startsWith("namespace ")) {
                if (namespace.getStart() == blankPosition) {
                    namespace.setStart(new Position(lineNumber, 0));
                    namespace.setEnd(new Position(lineNumber, line.length() - 1));
                }
            } else if (line.trim().startsWith("use ")) {
                String i = line.trim().split("use ", 2)[1].trim();
                if (useBlock.getStart() == blankPosition) {
                    imports.add(i);
                    useBlock.setStart(new Position(lineNumber, 0));
                    useBlock.setEnd(new Position(lineNumber, line.length()));
                } else if (collectUseBlock) {
                    imports.add(i);
                    useBlock.setEnd(new Position(lineNumber, line.length()));
                }
            } else if (line.isBlank()) {
                if (collectUseBlock)
                    useBlock.setEnd(new Position(lineNumber, line.length()));
            } else if (line.trim().startsWith("//")) {

            } else {
                collectUseBlock = false;
            }

            lineNumber++;
        }

        boolean blankSeparated = false;
        // Next, we reduce the use block to the last use statement, ignoring newlines
        // It's important to do so to make sure we don't multiply newlines
        // unnecessarily.
        if (useBlock.getStart() != blankPosition) {
            while (lines.get(useBlock.getEnd().getLine()).isBlank()) {
                blankSeparated = true;
                int curLine = useBlock.getEnd().getLine();
                useBlock.getEnd().setLine(curLine - 1);
                useBlock.getEnd().setCharacter(lines.get(curLine - 1).length());
            }
        } else if (namespace.getStart() != blankPosition) {
            int namespaceLine = namespace.getStart().getLine();
            if (namespaceLine < lines.size() - 1) {
                blankSeparated = lines.get(namespaceLine + 1).isBlank();
            }
        }

        return new DocumentPreamble(namespace, useBlock, imports, blankSeparated);
    }

    private String findToken(String path, Position p) throws IOException {
        List<String> contents = textBufferContents(path);

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
        File original = fileUri(params.getTextDocument());
        File tempFile = null;

        // LspLog.println("Change params: " + params);

        try {
            if (params.getContentChanges().size() > 0) {
                tempFile = designatedTemporaryFile(original);
                String contents = params.getContentChanges().get(0).getText();

                unstableContents(original, contents);

                Files.write(tempFile.toPath(), contents.getBytes());
            }

        } catch (Exception e) {
            LspLog.println("Failed to write temporary contents for file " + original + " into temporary file "
                    + tempFile + " : " + e);
        }

        report(recompile(original, Optional.ofNullable(tempFile)));
    }

    private void stableContents(File file) {
        this.temporaryContents.remove(file);
    }

    private void unstableContents(File file, String contents) {
        LspLog.println("Hashed filename " + file + " into " + designatedTemporaryFile(file));
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

        byFile.forEach((key, value) -> diagnostics.add(createDiagnostics(key.toURI().toString(),
                value.stream().map(ProtocolAdapter::toDiagnostic).collect(Collectors.toList()))));

        return diagnostics;

    }

    public void clearAllDiagnostics() {
        report(Either.forRight(createPerFileDiagnostics(this.project.getModel().getValidationEvents(),
                this.project.getSmithyFiles())));
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
        // File latestContents = temporary.orElse(path);
        Either<Exception, SmithyProject> loadedModel;
        if (!temporary.isPresent()) {
            // if there's no temporary file present (didOpen/didClose/didSave)
            // we want to rebuild the model with the original path
            // optionally removing a temporary file
            // This protects against a conflict during the didChange -> didSave sequence
            loadedModel = this.project.recompile(path, designatedTemporaryFile(path));
        } else {
            // If there's a temporary file present (didChange), we want to
            // replace the original path with a temporary one (to avoid conflicting
            // definitions)
            loadedModel = this.project.recompile(temporary.get(), path);
        }

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
                    "Recompiling " + path + " (with temporary content " + temporary + ") raised " + events.size()
                            + "  diagnostics");
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
