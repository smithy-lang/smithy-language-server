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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import smithyfmt.Formatter;
import smithyfmt.Result;
import software.amazon.smithy.lsp.codeactions.SmithyCodeActions;
import software.amazon.smithy.lsp.diagnostics.VersionDiagnostics;
import software.amazon.smithy.lsp.editor.SmartInput;
import software.amazon.smithy.lsp.ext.Completions;
import software.amazon.smithy.lsp.ext.Document;
import software.amazon.smithy.lsp.ext.DocumentPreamble;
import software.amazon.smithy.lsp.ext.LspLog;
import software.amazon.smithy.lsp.ext.SmithyCompletionItem;
import software.amazon.smithy.lsp.ext.SmithyProject;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.NeighborProviderIndex;
import software.amazon.smithy.model.loader.ParserUtils;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.SimpleParser;

public class SmithyTextDocumentService implements TextDocumentService {

    private Optional<LanguageClient> client;
    private SmithyProject project;

    /**
     * @param client Language Client to be used by text document service.
     */
    public SmithyTextDocumentService(Optional<LanguageClient> client) {
        this.client = client;
    }

    public void setProject(SmithyProject project) {
        this.project = project;
    }

    public void setClient(LanguageClient client) {
        this.client = Optional.of(client);
    }

    public Optional<File> getRoot() {
        return Optional.ofNullable(project).map(SmithyProject::getRoot);
    }

    /**
     * Discovers Smithy build files and loads the smithy project defined by them.
     *
     * @param root workspace root
     */
    public void createProject(File root) {
        LspLog.println("Recreating project from " + root);
        SmithyProject project = SmithyProject.forDirectory(root);
        this.project = project;
        if (project.isBroken()) {
            sendError("Failed to load the build. Encountered the following problems:\n"
                    + String.join("\n", project.getErrors()));
        } else {
            report(Either.forRight(createPerFileDiagnostics(this.project)));
            sendInfo("Project loaded with " + project.getExternalJars().size() + " external jars and "
                    + project.getSmithyFiles().size() + " discovered smithy files");
        }
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        LspLog.println("Asking to complete " + params + " in class " + params.getTextDocument().getClass());

        try {
            URI documentUri = Utils.createUri(params.getTextDocument().getUri());
            String token = findToken(documentUri, params.getPosition());
            DocumentPreamble preamble = Document.detectPreamble(textBufferContents(documentUri));

            boolean isTraitShapeId = isTraitShapeId(documentUri, params.getPosition());
            Optional<ShapeId> target;
            if (isTraitShapeId) {
                target = getTraitTarget(documentUri, params.getPosition(), preamble.getCurrentNamespace());
            } else {
                target = Optional.empty();
            }

            List<SmithyCompletionItem> smithyCompletionItems = Completions.find(this.project.getModel(), token,
                    isTraitShapeId, target);

            List<CompletionItem> items = Completions.resolveImports(smithyCompletionItems, preamble);

            LspLog.println("Completion items: " + items);

            return Utils.completableFuture(Either.forLeft(items));
        } catch (Exception e) {
            LspLog.println(
                    "Failed to identify token for completion in " + params.getTextDocument().getUri() + ": " + e);
            e.printStackTrace();
        }
        return Utils.completableFuture(Either.forLeft(ListUtils.of()));
    }

    // Determine the target of a trait, if present.
    private Optional<ShapeId> getTraitTarget(URI documentUri, Position position, Optional<String> namespace)
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
        return namespace.map(s -> ShapeId.fromParts(s, name));
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
    private boolean isTraitShapeId(URI documentUri, Position position) throws IOException {
        String line = textBufferContents(documentUri).get(position.getLine());
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

    private List<String> textBufferContents(URI uri) throws IOException {
        String modelFileContents = this.project.getModelFiles().get(uri);
        if (modelFileContents == null) {
            throw new FileNotFoundException(uri + " not found in model files. Existing model files are: "
                    + this.project.getModelFiles().keySet());
        }
        return Arrays.asList(modelFileContents.split(System.lineSeparator()));
    }

    private String findToken(URI uri, Position p) throws IOException {
        List<String> contents = textBufferContents(uri);

        String line = contents.get(p.getLine());
        int col = p.getCharacter();

        LspLog.println("Trying to find a token in line '" + line + "' at position " + p);

        String before = line.substring(0, col);
        String after = line.substring(col);

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
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(
        DocumentSymbolParams params
    ) {
        try {
            Map<ShapeId, Location> locations = project.getLocations();
            Model model = project.getModel().unwrap();

            List<DocumentSymbol> symbols = new ArrayList<>();

            URI documentUri = Utils.createUri(params.getTextDocument().getUri());

            locations.forEach((shapeId, loc) -> {
                boolean matchesDocument = documentUri.equals(Utils.createUri(loc.getUri()));

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
            URI uri = Utils.createUri(params.getTextDocument().getUri());
            Optional<ShapeId> initialShapeId = project.getShapeIdFromLocation(uri, params.getPosition());
            String found = findToken(uri, params.getPosition());
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

            return Utils.completableFuture(Either.forLeft(ListUtils.of()));
        }
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        Hover hover = new Hover();
        MarkupContent content = new MarkupContent();
        content.setKind("markdown");
        URI uri = Utils.createUri(params.getTextDocument().getUri());
        Position position = params.getPosition();
        LspLog.println("Trying to find hover content at " + uri + "[" + position.getLine() + ", "
                + position.getCharacter() + "]");
        Optional<ShapeId> initialShapeId = project.getShapeIdFromLocation(uri, params.getPosition());

        // TODO More granular error handling
        try {
            Shape shapeToSerialize;
            Model model = project.getModel().unwrap();
            String token = findToken(uri, params.getPosition());
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
                                .map(Trait::toShapeId);
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
        URI uri = Utils.createUri(params.getTextDocument().getUri());
        if (params.getContentChanges().size() > 0) {
            String contents = params.getContentChanges().get(0).getText();
            SmithyProject reloaded = this.project.reloadWithChanges(uri, contents);
            report(handleReloadedProject(reloaded));
        }
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String rawUri = params.getTextDocument().getUri();
        URI uri = Utils.createUri(rawUri);
        String contents = params.getTextDocument().getText();
        if (Utils.isFile(rawUri)) {
            SmithyProject reloaded = this.project.reloadWithChanges(uri, contents);
            report(handleReloadedProject(reloaded));
        }
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        SmithyProject reloaded = this.project.reload();
        report(handleReloadedProject(reloaded));
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        SmithyProject reloaded = this.project.reload();
        report(handleReloadedProject(reloaded));
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        URI uri = Utils.createUri(params.getTextDocument().getUri());
        File file = new File(uri);
        final CompletableFuture<List<? extends TextEdit>> emptyResult =
            Utils.completableFuture(Collections.emptyList());

        final Optional<SmartInput> content = Utils.optOr(
                Optional.ofNullable(this.project.getModelFiles().get(uri))
                        .map(SmartInput::fromInput), () -> SmartInput.fromPathSafe(file.toPath()));

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

    /**
     * @param result Either a fatal error message, or a list of diagnostics to
     *               publish
     */
    private void report(Either<String, List<PublishDiagnosticsParams>> result) {
        client.ifPresent(cl -> {
            if (result.isLeft()) {
                cl.showMessage(new MessageParams(MessageType.Error, result.getLeft()));
            } else {
                result.getRight().forEach(cl::publishDiagnostics);
            }
        });
    }

    private Either<String, List<PublishDiagnosticsParams>> handleReloadedProject(SmithyProject result) {
        // TODO: For now, don't update the project unless it isn't broken. We will have to see if this is a good
        //  experience or not.
        if (result.isBroken()) {
            return Either.forLeft("Failed to load project:\n" + String.join("\n", result.getErrors()));
        }
        this.project = result;
        return Either.forRight(createPerFileDiagnostics(result));
    }

    /**
     * Breaks down a list of validation events into a per-file list of diagnostics,
     * explicitly publishing an empty list of diagnostics for files not present in
     * validation events.
     *
     * @param project Smithy project to create per file diagnostics for
     * @return a list of LSP diagnostics to publish
     */
    static List<PublishDiagnosticsParams> createPerFileDiagnostics(SmithyProject project) {
        // URI is used because conversion toString deals with platform specific path separator
        Map<URI, List<Diagnostic>> byUri = new HashMap<>();

        for (ValidationEvent ev : project.getModel().getValidationEvents()) {
            URI uri = Utils.createUri(ev.getSourceLocation().getFilename());
            if (byUri.containsKey(uri)) {
                byUri.get(uri).add(ProtocolAdapter.toDiagnostic(ev));
            } else {
                List<Diagnostic> l = new ArrayList<>();
                l.add(ProtocolAdapter.toDiagnostic(ev));
                byUri.put(uri, l);
            }
        }

        project.getSmithyFiles().forEach(f -> {
            List<Diagnostic> versionDiagnostics = VersionDiagnostics.createVersionDiagnostics(f,
                    project.getModelFiles());
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
