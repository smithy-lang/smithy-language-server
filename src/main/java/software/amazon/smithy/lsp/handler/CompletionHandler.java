/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;
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
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentPositionContext;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.RangeAdapter;
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

    public CompletionHandler(Project project) {
        this.project = project;
    }

    /**
     * @param params The request params
     * @return A list of possible completions
     */
    public List<CompletionItem> handle(CompletionParams params, CancelChecker cc) {
        String uri = params.getTextDocument().getUri();
        SmithyFile smithyFile = project.getSmithyFile(uri);
        if (smithyFile == null) {
            return Collections.emptyList();
        }

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

        String token = smithyFile.getDocument().copyToken(position);
        if (token == null || token.isEmpty()) {
            return Collections.emptyList();
        }
        String matchToken = token.toLowerCase();


        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        if (!project.getModelResult().getResult().isPresent()) {
            return Collections.emptyList();
        }
        Model model = project.getModelResult().getResult().get();
        DocumentPositionContext context = DocumentParser.forDocument(smithyFile.getDocument())
                .determineContext(position);

        if (cc.isCanceled()) {
            return Collections.emptyList();
        }

        return contextualShapes(model, context)
                .filter(shape -> shape.getId().getName().toLowerCase().startsWith(matchToken))
                // TODO: Use mapMulti when we upgrade jdk>16
                .collect(ArrayList::new, completionsFactory(context, model, smithyFile), ArrayList::addAll);
    }

    private static BiConsumer<ArrayList<CompletionItem>, Shape> completionsFactory(
            DocumentPositionContext context,
            Model model,
            SmithyFile smithyFile
    ) {
        TraitBodyVisitor visitor = new TraitBodyVisitor(model);
        return (acc, shape) -> {
            String shapeName = shape.getId().getName();
            switch (context) {
                case TRAIT:
                    String traitBody = shape.accept(visitor);
                    // Strip outside pair of brackets from any structure traits.
                    if (!traitBody.isEmpty() && traitBody.charAt(0) == '{') {
                        traitBody = traitBody.substring(1, traitBody.length() - 1);
                    }

                    if (!traitBody.isEmpty()) {
                        CompletionItem traitWithMembersItem = createCompletion(shapeName + "(" + traitBody + ")");
                        addTextEdits(traitWithMembersItem, shape.getId(), smithyFile);
                        acc.add(traitWithMembersItem);
                    }
                    CompletionItem defaultCompletionItem;
                    if (shape.isStructureShape() && !shape.members().isEmpty()) {
                        defaultCompletionItem = createCompletion(shapeName + "()");
                    } else {
                        defaultCompletionItem = createCompletion(shapeName);
                    }
                    addTextEdits(defaultCompletionItem, shape.getId(), smithyFile);
                    acc.add(defaultCompletionItem);
                    break;
                case MEMBER_TARGET:
                case MIXIN:
                    CompletionItem item = createCompletion(shapeName);
                    addTextEdits(item, shape.getId(), smithyFile);
                    acc.add(item);
                    break;
                case SHAPE_DEF:
                case OTHER:
                default:
                    break;
            }
        };
    }

    private static void addTextEdits(CompletionItem completionItem, ShapeId shapeId, SmithyFile smithyFile) {
        String importId = shapeId.toString();
        String importNamespace = shapeId.getNamespace();
        CharSequence currentNamespace = smithyFile.getNamespace();

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
        String insertText = "\n" + "use " + importId;
        // We can only know where to put the import if there's already use statements, or a namespace
        if (smithyFile.getDocumentImports().isPresent()) {
            Range importsRange = smithyFile.getDocumentImports().get().importsRange();
            Range editRange = RangeAdapter.point(importsRange.getEnd());
            return new TextEdit(editRange, insertText);
        } else if (smithyFile.getDocumentNamespace().isPresent()) {
            Range namespaceStatementRange = smithyFile.getDocumentNamespace().get().statementRange();
            Range editRange = RangeAdapter.point(namespaceStatementRange.getEnd());
            return new TextEdit(editRange, insertText);
        }

        return null;
    }

    private Stream<Shape> contextualShapes(Model model, DocumentPositionContext context) {
        switch (context) {
            case TRAIT:
                return model.getShapesWithTrait(TraitDefinition.class).stream();
            case MEMBER_TARGET:
                return model.shapes()
                        .filter(shape -> !shape.isMemberShape())
                        .filter(shape -> !shape.hasTrait(TraitDefinition.class));
            case MIXIN:
                return model.getShapesWithTrait(MixinTrait.class).stream();
            case SHAPE_DEF:
            case OTHER:
            default:
                return Stream.empty();
        }
    }

    private static CompletionItem createCompletion(String label) {
        CompletionItem completionItem = new CompletionItem(label);
        completionItem.setKind(CompletionItemKind.Class);
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
            return "\"\"";
        }

        @Override
        public String unionShape(UnionShape shape) {
            return "{}";
        }
    }
}
