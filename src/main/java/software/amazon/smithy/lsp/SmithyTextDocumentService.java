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
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import javax.annotation.Nullable;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import smithyfmt.Formatter;
import smithyfmt.Result;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.FileCacheResolver;
import software.amazon.smithy.cli.dependencies.MavenDependencyResolver;
import software.amazon.smithy.lsp.codeactions.SmithyCodeActions;
import software.amazon.smithy.lsp.diagnostics.VersionDiagnostics;
import software.amazon.smithy.lsp.editor.SmartInput;
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
import software.amazon.smithy.model.loader.ParserUtils;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.SimpleParser;

public class SmithyTextDocumentService implements TextDocumentService {

    private final List<CompletionItem> baseCompletions = new ArrayList<>();
    private Optional<LanguageClient> client;
    private final List<Location> noLocations = Collections.emptyList();
    @Nullable
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
     * @param tempFile Temporary File to be used by text document service.
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
        DependencyResolver resolver = createDependencyResolver(root, ext.getLastModifiedInMillis());
        Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, root, resolver);
        if (loaded.isRight()) {
            SmithyProject project = loaded.getRight();
            this.project = project;
            clearAllDiagnostics();
            sendInfo("Project loaded with " + project.getExternalJars().size() + " external jars and "
                    + project.getSmithyFiles().size() + " discovered smithy files");
        } else {
            sendError(
                "Failed to create Smithy project. See output panel for details. Uncaught exception: "
                    + loaded.getLeft().toString()
            );
            loaded.getLeft().printStackTrace();
        }
    }

    private DependencyResolver createDependencyResolver(File root, long lastModified) {
        Path buildPath = Paths.get(root.toString(), "build", "smithy");
        File buildDir = new File(buildPath.toString());
        if (!buildDir.exists()) {
            buildDir.mkdirs();
        }
        Path cachePath = Paths.get(buildPath.toString(), "classpath.json");
        File dependencyCache = new File(cachePath.toString());
        if (!dependencyCache.exists()) {
            try {
                Files.createFile(cachePath);
            } catch (IOException e) {
                LspLog.println("Could not create dependency cache file " + e);
            }
        }
        MavenDependencyResolver delegate = new MavenDependencyResolver();
        return new FileCacheResolver(dependencyCache, lastModified, delegate);
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
                    e.printStackTrace();
                    brokenFiles.add(smithyBuild.toString());
                }
            }
        }

        if (brokenFiles.isEmpty()) {
            createProject(result.build(), root);
        } else {
            sendError(
                "Failed to load the build, the following build files have problems: \n"
                    + String.join("\n", brokenFiles)
            );
        }
    }

    private MessageParams msg(final MessageType sev, final String cont) {
        return new MessageParams(sev, cont);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        LspLog.println("Asking to complete " + params + " in class " + params.getTextDocument().getClass());

        try {
            String documentUri = params.getTextDocument().getUri();
            String token = findToken(documentUri, params.getPosition());
            DocumentPreamble preamble = Document.detectPreamble(textBufferContents(documentUri));

            boolean isTraitShapeId = isTraitShapeId(documentUri, params.getPosition());
            Optional<ShapeId> target = Optional.empty();
            if (isTraitShapeId) {
                target = getTraitTarget(documentUri, params.getPosition(), preamble.getCurrentNamespace());
            }

            List<CompletionItem> items = Completions.resolveImports(project.getCompletions(token, isTraitShapeId,
                            target),
                    preamble);
            LspLog.println("Completion items: " + items);

            return Utils.completableFuture(Either.forLeft(items));
        } catch (Exception e) {
            LspLog.println(
                    "Failed to identify token for completion in " + params.getTextDocument().getUri() + ": " + e);
            e.printStackTrace();
        }
        return Utils.completableFuture(Either.forLeft(baseCompletions));
    }

    // Determine the target of a trait, if present.
    private Optional<ShapeId> getTraitTarget(String documentUri, Position position, Optional<String> namespace)
            throws IOException {
        List<String> contents = textBufferContents(documentUri);
        String currentLine = contents.get(position.getLine()).trim();
        if (currentLine.startsWith("apply")) {
            return getApplyStatementTarget(currentLine, namespace);
        }

        // Iterate through the rest of the model file, skipping docs and other traits to get trait's target.
        for (int i = position.getLine() + 1; i < contents.size(); i++) {
            String line = contents.get(i).trim();
            // If an empty line is encountered, assume the trait's target has not yet been written.
            if (line.equals("")) {
                return Optional.empty();
            // Skip comments lines
            } else if (line.startsWith("//")) {
            // Skip other traits.
            } else if (line.startsWith("@")) {
                // Jump to end of trait.
                i = getEndOfTrait(i, contents);
            } else {
                // Offset the target shape position by accounting for leading whitespace.
                String originalLine = contents.get(i);
                int offset = 1;
                while (originalLine.charAt(offset) == ' ') {
                    offset++;
                }
                return project.getShapeIdFromLocation(documentUri, new Position(i, offset));
            }
        }
        return Optional.empty();
    }

    // Determine target shape from an apply statement.
    private Optional<ShapeId> getApplyStatementTarget(String applyStatement, Optional<String> namespace) {
        SimpleParser parser = new SimpleParser(applyStatement);
        parser.expect('a');
        parser.expect('p');
        parser.expect('p');
        parser.expect('l');
        parser.expect('y');
        parser.ws();
        String name = ParserUtils.parseShapeId(parser);
        if (namespace.isPresent()) {
            return Optional.of(ShapeId.fromParts(namespace.get(), name));
        }
        return Optional.empty();
    }

    // Find the line where the trait ends.
    private int getEndOfTrait(int lineNumber, List<String> contents) {
        String line = contents.get(lineNumber);
        if (line.contains("(")) {
            if (hasClosingParen(line)) {
                return lineNumber;
            }
            for (int i = lineNumber + 1; i < contents.size(); i++) {
                String nextLine = contents.get(i).trim();
                if (hasClosingParen(nextLine)) {
                    return i;
                }
            }
        }
        return lineNumber;
    }

    // Determine if the line has an unquoted closing parenthesis.
    private boolean hasClosingParen(String line) {
        boolean quote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && !quote) {
                quote = true;
            } else if (c == '"' && quote) {
                quote = false;
            }

            if (c == ')' && !quote) {
                return true;
            }
        }
        return false;
    }

    // Work backwards from current position to determine if position is part of a trait shapeId.
    private boolean isTraitShapeId(String documentUri, Position position) throws IOException {
        String line = getLine(textBufferContents(documentUri), position);
        for (int i = position.getCharacter() - 1; i >= 0; i--) {
            char c = line.charAt(i);
            if (c == '@') {
                return true;
            }
            if (c == ' ') {
                return false;
            }
        }
        return false;
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

    /**
     * @return lines in the file or buffer
     */
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

    private String getLine(List<String> lines, Position position) {
        return lines.get(position.getLine());
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
        DocumentSymbolParams params
    ) {
        try {
            Map<ShapeId, Location> locations = project.getLocations();
            Model model = project.getModel().unwrap();

            List<DocumentSymbol> symbols = new ArrayList<>();

            URI documentUri = documentIdentifierToUri(params.getTextDocument());

            locations.forEach((shapeId, loc) -> {
                String[] locSegments = loc.getUri().replace("\\", "/").split(":");
                boolean matchesDocument = documentUri.toString().endsWith(locSegments[locSegments.length - 1]);

                if (!matchesDocument) {
                    return;
                }

                Shape shape = model.expectShape(shapeId);

                Optional<ShapeType> parentType = shape.isMemberShape()
                    ? Optional.of(model.expectShape(shapeId.withoutMember()).getType())
                    : Optional.empty();

                SymbolKind kind = ProtocolAdapter.toSymbolKind(shape.getType(), parentType);

                String symbolName = shapeId.getMember().orElse(shapeId.getName());

                symbols.add(new DocumentSymbol(symbolName, kind, loc.getRange(), loc.getRange()));
            });

            return Utils.completableFuture(
                symbols
                    .stream()
                    .map(Either::<SymbolInformation, DocumentSymbol>forRight)
                    .collect(Collectors.toList())
            );
        } catch (Exception e) {
            e.printStackTrace();

            return Utils.completableFuture(Collections.emptyList());
        }
    }

    private URI documentIdentifierToUri(TextDocumentIdentifier ident) throws UnsupportedEncodingException {
        return Utils.isSmithyJarFile(ident.getUri())
            ? URI.create(URLDecoder.decode(ident.getUri(), StandardCharsets.UTF_8.name()))
            : this.fileUri(ident).toURI();
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        // TODO More granular error handling
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
                Optional<Shape> target = getTargetShape(initialShape, found, model);

                // Use location of target shape or default to the location of the initial shape.
                ShapeId shapeId = target.map(Shape::getId).orElse(initialShapeId.get());
                Location shapeLocation = project.getLocations().get(shapeId);
                locations = Collections.singletonList(shapeLocation);
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
    public CompletableFuture<Hover> hover(HoverParams params) {
        Hover hover = new Hover();
        MarkupContent content = new MarkupContent();
        content.setKind("markdown");
        Optional<ShapeId> initialShapeId = project.getShapeIdFromLocation(params.getTextDocument().getUri(),
                params.getPosition());
        // TODO More granular error handling
        try {
            Shape shapeToSerialize;
            Model model = project.getModel().unwrap();
            String token = findToken(params.getTextDocument().getUri(), params.getPosition());
            LspLog.println("Found token: " + token);
            if (initialShapeId.isPresent()) {
                Shape initialShape = model.getShape(initialShapeId.get()).get();
                Optional<Shape> target = initialShape.asMemberShape()
                        .map(memberShape -> model.getShape(memberShape.getTarget()))
                        .orElse(getTargetShape(initialShape, token, model));
                shapeToSerialize = target.orElse(initialShape);
            } else {
                shapeToSerialize = model.shapes()
                        .filter(shape -> !shape.isMemberShape())
                        .filter(shape -> shape.getId().getName().equals(token))
                        .findAny()
                        .orElse(null);
            }

            if (shapeToSerialize != null) {
                content.setValue(getHoverContentsForShape(shapeToSerialize, model));
            }
        } catch (Exception e) {
            LspLog.println("Failed to determine hover content: " + e);
            e.printStackTrace();
        }

        hover.setContents(content);
        return Utils.completableFuture(hover);
    }

    // Finds the first non-member neighbor shape or trait applied to a member whose name matches the token.
    private Optional<Shape> getTargetShape(Shape initialShape, String token, Model model) {
        LspLog.println("Finding target of: " + initialShape);
        Walker shapeWalker = new Walker(NeighborProviderIndex.of(model).getProvider());
        return shapeWalker.walkShapes(initialShape).stream()
                .flatMap(shape -> {
                    if (shape.isMemberShape()) {
                        return shape.getAllTraits().values().stream()
                                .map(trait -> trait.toShapeId());
                    } else {
                        return Stream.of(shape.getId());
                    }
                })
                .filter(shapeId -> shapeId.getName().equals(token))
                .map(shapeId -> model.getShape(shapeId).get())
                .findFirst();
    }

    private String getHoverContentsForShape(Shape shape, Model model) {
        List<ValidationEvent> validationEvents = getValidationEventsForShape(shape);
        String serializedShape = serializeShape(shape, model);
        if (validationEvents.isEmpty()) {
            return "```smithy\n" + serializedShape + "\n```";
        }
        StringBuilder contents = new StringBuilder();
        contents.append("```smithy\n");
        contents.append(serializedShape);
        contents.append("\n");
        contents.append("---\n");
        for (ValidationEvent event : validationEvents) {
            contents.append(event.getSeverity() + ": " + event.getMessage() + "\n");
        }
        contents.append("```");
        return contents.toString();
    }

    private String serializeShape(Shape shape, Model model) {
        SmithyIdlModelSerializer serializer = SmithyIdlModelSerializer.builder()
                .metadataFilter(key -> false)
                .shapeFilter(s -> s.getId().equals(shape.getId()))
                .serializePrelude().build();
        Map<Path, String> serialized = serializer.serialize(model);
        Path path = Paths.get(shape.getId().getNamespace() + ".smithy");
        return serialized.get(path).trim();
    }

    private List<ValidationEvent> getValidationEventsForShape(Shape shape) {
        return project.getModel().getValidationEvents().stream()
                .filter(validationEvent -> shape.getId().equals(validationEvent.getShapeId().orElse(null)))
                .collect(Collectors.toList());
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        List<Either<Command, CodeAction>> versionCodeActions =
                SmithyCodeActions.versionCodeActions(params).stream()
                        .map(Either::<Command, CodeAction>forRight)
                        .collect(Collectors.toList());

        return Utils.completableFuture(versionCodeActions);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        File original = fileUri(params.getTextDocument());
        File tempFile = null;

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
            e.printStackTrace();
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

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        File file = fileUri(params.getTextDocument());
        final CompletableFuture<List<? extends TextEdit>> emptyResult =
            Utils.completableFuture(Collections.emptyList());

        final Optional<SmartInput> content = Utils.optOr(
                Optional.ofNullable(temporaryContents.get(file)).map(SmartInput::fromInput),
                () -> SmartInput.fromPathSafe(file.toPath())
        );
        if (content.isPresent()) {
            SmartInput input = content.get();
            final Result result = Formatter.format(input.getInput());
            final Range fullRange = input.getRange();
            if (result.isSuccess() && !result.getValue().equals(input.getInput())) {
                return Utils.completableFuture(Collections.singletonList(new TextEdit(
                        fullRange,
                        result.getValue()
                )));
            } else if (!result.isSuccess()) {
                LspLog.println("Failed to format: " + result.getError());
                return emptyResult;
            } else {
                return emptyResult;
            }
        } else {
            LspLog.println("Content is unavailable, not formatting.");
            return emptyResult;
        }
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
        Map<URI, List<Diagnostic>> byUri = new HashMap<>();

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
                byUri.get(finalUri).add(ProtocolAdapter.toDiagnostic(ev));
            } else {
                List<Diagnostic> l = new ArrayList<>();
                l.add(ProtocolAdapter.toDiagnostic(ev));
                byUri.put(finalUri, l);
            }
        }

        allFiles.forEach(f -> {
            List<Diagnostic> versionDiagnostics = VersionDiagnostics.createVersionDiagnostics(f, temporaryContents);
            if (!byUri.containsKey(f.toURI())) {
                byUri.put(f.toURI(), versionDiagnostics);
            } else {
                byUri.get(f.toURI()).addAll(versionDiagnostics);
            }
        });

        List<PublishDiagnosticsParams> diagnostics = new ArrayList<>();
        byUri.forEach((key, value) -> diagnostics.add(new PublishDiagnosticsParams(key.toString(), value)));
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
