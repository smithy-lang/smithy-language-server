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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
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
import software.amazon.smithy.lsp.ext.Completions;
import software.amazon.smithy.lsp.ext.Constants;
import software.amazon.smithy.lsp.ext.Document;
import software.amazon.smithy.lsp.ext.DocumentPreamble;
import software.amazon.smithy.lsp.ext.LspLog;
import software.amazon.smithy.lsp.ext.SmithyBuildLoader;
import software.amazon.smithy.lsp.ext.SmithyProject;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.knowledge.NeighborProviderIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public class SmithyTextDocumentService implements TextDocumentService {

    private final List<CompletionItem> baseCompletions = new ArrayList<>();
    private Optional<LanguageClient> client;
    private final List<Location> noLocations = Collections.emptyList();
    private SmithyProject project;
    private final File temporaryFolder;

    // when files are edited, their contents will be persisted in memory and removed
    // on didSave or didClose
    private final Map<File, String> temporaryContents = new ConcurrentHashMap<>();

    // We use this function to hash filepaths to the same location in temporary
    // folder
    private final HashFunction hash = Hashing.murmur3_128();

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
        return Optional.ofNullable(project).map(SmithyProject::getRoot);
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
        List<String> brokenFiles = new ArrayList<>();

        for (String file : Constants.BUILD_FILES) {
            File smithyBuild = Paths.get(root.getAbsolutePath(), file).toFile();
            if (smithyBuild.isFile()) {
                try {
                    SmithyBuildExtensions local = SmithyBuildLoader.load(smithyBuild.toPath());
                    result.merge(local);
                    LspLog.println("Loaded build extensions " + local + " from " + smithyBuild.getAbsolutePath());
                } catch (Exception e) {
                    LspLog.println("Failed to load config from" + smithyBuild + ": " + e);
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
            DocumentPreamble preamble = Document.detectPreamble(textBufferContents(documentUri));
            LspLog.println("Token for completion: " + found + " in class " + position.getTextDocument().getClass());

            List<CompletionItem> items = Completions.resolveImports(project.getCompletions(found), preamble);

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
                try {
                    contents = readAll(new File(URI.create(path)));
                } catch (IllegalArgumentException e) {
                    contents = readAll(new File(path));
                }
            }

        }

        return contents;
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
            // This attempts to return the definition location that corresponds to a position within a text document.
            // First, the position is used to find any shapes in the model that are defined at that location. Next,
            // a token is extracted from the raw text document. The model is walked from the starting shapeId and any
            // the locations of neighboring shapes that match the token are returned. For example, if the position
            // is the input of an operation, the token will be the name of the input structure, and the operation will
            // be walked to return the location of where the input structure is defined. This allows go-to-definition
            // to jump from the input of the operation, to where the input structure is actually defined.
            List<Location> locations;
            Optional<ShapeId> initialShapeId = project.getShapeIdFromLocation(params.getTextDocument().getUri(),
                    params.getPosition());
            String found = findToken(params.getTextDocument().getUri(), params.getPosition());
            if (initialShapeId.isPresent()) {
                Model model = project.getModel().unwrap();
                Shape initialShape = model.getShape(initialShapeId.get()).get();
                // Find the first non-member neighbor shape or trait applied to a member whose name matches the token.
                Walker shapeWalker = new Walker(NeighborProviderIndex.of(model).getProvider());
                Optional<ShapeId> target = shapeWalker.walkShapes(initialShape).stream()
                        .flatMap(shape -> {
                            if (shape.isMemberShape()) {
                                return shape.getAllTraits().values().stream()
                                        .map(trait -> trait.toShapeId());
                            } else {
                                return Stream.of(shape.getId());
                            }
                        })
                        .filter(shapeId -> shapeId.getName().equals(found))
                        .findFirst();
                // Use location on target, or else default to initial shape.
                locations = Collections.singletonList(project.getLocations().get(target.orElse(initialShapeId.get())));
            } else {
                // If the definition params do not have a matching shape at that location, return locations of all
                // shapes that match token by shape name. This makes it possible link the shape name in a line
                // comment to its definition.
                locations = project.getLocations().entrySet().stream()
                        .filter(entry -> entry.getKey().getName().equals(found))
                        .map(Map.Entry::getValue)
                        .collect(Collectors.toList());
            }
            return Utils.completableFuture(Either.forLeft(locations));
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
        try {
            return new File(URI.create(uri));
        } catch (IllegalArgumentException e) {
            return new File(uri);
        }
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
        // URI is used because conversion toString deals with platform specific path separator
        Map<URI, List<ValidationEvent>> byUri = new HashMap<>();

        for (ValidationEvent ev : events) {
            URI finalUri;
            try {
                // can be a uri in the form of jar:file:/some-path
                // if we have a jar we go to smithyjar
                // else we make sure `file:` scheme is used
                String fileName = ev.getSourceLocation().getFilename();
                String uri = Utils.isJarFile(fileName)
                    ? Utils.toSmithyJarFile(fileName)
                    : !Utils.isFile(fileName) ? "file:" + fileName
                    : fileName;
                finalUri = new URI(uri);
            } catch (URISyntaxException ex) {
                // can also be something like C:\Some\path in which case creating a URI will fail
                // so after a file conversion, we call .toURI to produce a standard `file:/C:/Some/path`
                finalUri = new File(ev.getSourceLocation().getFilename()).toURI();
            }

            if (byUri.containsKey(finalUri)) {
                byUri.get(finalUri).add(ev);
            } else {
                List<ValidationEvent> l = new ArrayList<>();
                l.add(ev);
                byUri.put(finalUri, l);
            }
        }

        allFiles.forEach(f -> {
            if (!byUri.containsKey(f.toURI())) {
                byUri.put(f.toURI(), Collections.emptyList());
            }
        });

        List<PublishDiagnosticsParams> diagnostics = new ArrayList<>();

        byUri.forEach((key, value) -> diagnostics.add(
            new PublishDiagnosticsParams(
                key.toString(),
                value.stream().map(ProtocolAdapter::toDiagnostic).collect(Collectors.toList())
            )
        ));

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

            List<ValidationEvent> events = new ArrayList<>();
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

    /**
     * Run a selector expression against the loaded model in the workspace.
     * @param expression the selector expression
     * @return list of locations of shapes that match expression
     */
    public Either<Exception, List<Location>> runSelector(String expression) {
        return this.project.runSelector(expression);
    }

    private void sendInfo(String msg) {
        this.client.ifPresent(client -> client.showMessage(new MessageParams(MessageType.Info, msg)));
    }

    private void sendError(String msg) {
        this.client.ifPresent(client -> client.showMessage(new MessageParams(MessageType.Error, msg)));
    }

}
