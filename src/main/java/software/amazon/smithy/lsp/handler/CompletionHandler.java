/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentPositionContext;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.MixinTrait;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.TraitDefinition;

/**
 * Handles completion requests.
 */
public final class CompletionHandler {
    // TODO: Handle keyword completions
    private static final List<String> KEYWORDS = Arrays.asList("bigDecimal", "bigInteger", "blob", "boolean", "byte",
            "create", "collectionOperations", "delete", "document", "double", "errors", "float", "identifiers", "input",
            "integer", "integer", "key", "list", "long", "map", "member", "metadata", "namespace", "operation",
            "operations",
            "output", "put", "read", "rename", "resource", "resources", "service", "set", "short", "string",
            "structure",
            "timestamp", "union", "update", "use", "value", "version");

    private final Project project;
    private final SmithyFile smithyFile;

    public CompletionHandler(Project project, SmithyFile smithyFile) {
        this.project = project;
        this.smithyFile = smithyFile;
    }

    /**
     * @param params The request params
     * @return A list of possible completions
     */
    public List<CompletionItem> handle(CompletionParams params, CancelChecker cc) {
        // TODO: This method has to check for cancellation before using shared resources,
        //  and before performing expensive operations. If we have to change this, or do
        //  the same type of thing elsewhere, it would be nice to have some type of state
        //  machine abstraction or similar to make sure cancellation is properly checked.
        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        Position position = params.getPosition();
        CompletionContext completionContext = params.getContext();
        if (completionContext != null
            && completionContext.getTriggerKind().equals(CompletionTriggerKind.Invoked)
            && position.getCharacter() > 0) {
            // When the trigger is 'Invoked', the position is the next character
            position.setCharacter(position.getCharacter() - 1);
        }

        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        // TODO: Maybe we should only copy the token up to the current character
        DocumentId id = smithyFile.document().copyDocumentId(position);
        if (id == null || id.idSlice().isEmpty()) {
            return Collections.emptyList();
        }

        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        Optional<Model> modelResult = project.modelResult().getResult();
        if (modelResult.isEmpty()) {
            return Collections.emptyList();
        }
        Model model = modelResult.get();
        DocumentPositionContext context = DocumentParser.forDocument(smithyFile.document())
                .determineContext(position);

        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        return contextualShapes(model, context, smithyFile)
                .filter(contextualMatcher(id, context))
                .mapMulti(completionsFactory(context, model, smithyFile, id))
                .toList();
    }

    private static BiConsumer<Shape, Consumer<CompletionItem>> completionsFactory(
            DocumentPositionContext context,
            Model model,
            SmithyFile smithyFile,
            DocumentId id
    ) {
        TraitBodyVisitor visitor = new TraitBodyVisitor(model);
        boolean useFullId = shouldMatchOnAbsoluteId(id, context);
        return (shape, consumer) -> {
            String shapeLabel = useFullId
                    ? shape.getId().toString()
                    : shape.getId().getName();

            switch (context) {
                case TRAIT -> {
                    String traitBody = shape.accept(visitor);
                    // Strip outside pair of brackets from any structure traits.
                    if (!traitBody.isEmpty() && traitBody.charAt(0) == '{') {
                        traitBody = traitBody.substring(1, traitBody.length() - 1);
                    }

                    if (!traitBody.isEmpty()) {
                        CompletionItem traitWithMembersItem = createCompletion(
                                shapeLabel + "(" + traitBody + ")", shape.getId(), smithyFile, useFullId, id);
                        consumer.accept(traitWithMembersItem);
                    }

                    if (shape.isStructureShape() && !shape.members().isEmpty()) {
                        shapeLabel += "()";
                    }
                    CompletionItem defaultItem = createCompletion(shapeLabel, shape.getId(), smithyFile, useFullId, id);
                    consumer.accept(defaultItem);
                }
                case MEMBER_TARGET, MIXIN, USE_TARGET -> {
                    CompletionItem item = createCompletion(shapeLabel, shape.getId(), smithyFile, useFullId, id);
                    consumer.accept(item);
                }
                default -> {
                }
            }
        };
    }

    private static void addTextEdits(CompletionItem completionItem, ShapeId shapeId, SmithyFile smithyFile) {
        String importId = shapeId.toString();
        String importNamespace = shapeId.getNamespace();
        CharSequence currentNamespace = smithyFile.namespace();

        if (importNamespace.contentEquals(currentNamespace)
            || Prelude.isPreludeShape(shapeId)
            || smithyFile.hasImport(importId)) {
            return;
        }

        TextEdit textEdit = getImportTextEdit(smithyFile, importId);
        if (textEdit != null) {
            completionItem.setAdditionalTextEdits(Collections.singletonList(textEdit));
        }
    }

    private static TextEdit getImportTextEdit(SmithyFile smithyFile, String importId) {
        String insertText = System.lineSeparator() + "use " + importId;
        // We can only know where to put the import if there's already use statements, or a namespace
        if (smithyFile.documentImports().isPresent()) {
            Range importsRange = smithyFile.documentImports().get().importsRange();
            Range editRange = LspAdapter.point(importsRange.getEnd());
            return new TextEdit(editRange, insertText);
        } else if (smithyFile.documentNamespace().isPresent()) {
            Range namespaceStatementRange = smithyFile.documentNamespace().get().statementRange();
            Range editRange = LspAdapter.point(namespaceStatementRange.getEnd());
            return new TextEdit(editRange, insertText);
        }

        return null;
    }

    private static Stream<Shape> contextualShapes(Model model, DocumentPositionContext context, SmithyFile smithyFile) {
        return switch (context) {
            case TRAIT -> model.getShapesWithTrait(TraitDefinition.class).stream();
            case MEMBER_TARGET -> model.shapes()
                    .filter(shape -> !shape.isMemberShape())
                    .filter(shape -> !shape.hasTrait(TraitDefinition.class));
            case MIXIN -> model.getShapesWithTrait(MixinTrait.class).stream();
            case USE_TARGET -> model.shapes()
                    .filter(shape -> !shape.isMemberShape())
                    .filter(shape -> !shape.getId().getNamespace().contentEquals(smithyFile.namespace()))
                    .filter(shape -> !smithyFile.hasImport(shape.getId().toString()));
            default -> Stream.empty();
        };
    }

    private static Predicate<Shape> contextualMatcher(DocumentId id, DocumentPositionContext context) {
        String matchToken = id.copyIdValue().toLowerCase();
        if (shouldMatchOnAbsoluteId(id, context)) {
            return (shape) -> shape.getId().toString().toLowerCase().startsWith(matchToken);
        } else {
            return (shape) -> shape.getId().getName().toLowerCase().startsWith(matchToken);
        }
    }

    private static boolean shouldMatchOnAbsoluteId(DocumentId id, DocumentPositionContext context) {
        return context == DocumentPositionContext.USE_TARGET
                || id.type() == DocumentId.Type.NAMESPACE
                || id.type() == DocumentId.Type.ABSOLUTE_ID;
    }

    private static CompletionItem createCompletion(
            String label,
            ShapeId shapeId,
            SmithyFile smithyFile,
            boolean useFullId,
            DocumentId id
    ) {
        CompletionItem completionItem = new CompletionItem(label);
        completionItem.setKind(CompletionItemKind.Class);
        TextEdit textEdit = new TextEdit(id.range(), label);
        completionItem.setTextEdit(Either.forLeft(textEdit));
        if (!useFullId) {
            addTextEdits(completionItem, shapeId, smithyFile);
        }
        return completionItem;
    }

    private static final class TraitBodyVisitor extends ShapeVisitor.Default<String> {
        private final Model model;

        TraitBodyVisitor(Model model) {
            this.model = model;
        }

        @Override
        protected String getDefault(Shape shape) {
            return "";
        }

        @Override
        public String blobShape(BlobShape shape) {
            return "\"\"";
        }

        @Override
        public String booleanShape(BooleanShape shape) {
            return "true|false";
        }

        @Override
        public String listShape(ListShape shape) {
            return "[]";
        }

        @Override
        public String mapShape(MapShape shape) {
            return "{}";
        }

        @Override
        public String setShape(SetShape shape) {
            return "[]";
        }

        @Override
        public String stringShape(StringShape shape) {
            return "\"\"";
        }

        @Override
        public String structureShape(StructureShape shape) {
            List<String> entries = new ArrayList<>();
            for (MemberShape memberShape : shape.members()) {
                if (memberShape.hasTrait(RequiredTrait.class)) {
                    Shape targetShape = model.expectShape(memberShape.getTarget());
                    entries.add(memberShape.getMemberName() + ": " + targetShape.accept(this));
                }
            }
            return "{" + String.join(", ", entries) + "}";
        }

        @Override
        public String timestampShape(TimestampShape shape) {
            // TODO: Handle timestampFormat (which could indicate a numeric default)
            return "\"\"";
        }

        @Override
        public String unionShape(UnionShape shape) {
            return "{}";
        }
    }
}
