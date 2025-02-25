/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.ExternalDocumentationTrait;
import software.amazon.smithy.model.traits.IdRefTrait;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Handles hover requests for the Smithy IDL.
 */
public final class HoverHandler {
    /**
     * Empty markdown hover content.
     */
    public static final Hover EMPTY = new Hover(new MarkupContent("markdown", ""));

    private final Project project;
    private final IdlFile smithyFile;
    private final Severity minimumSeverity;

    /**
     * @param project Project the hover is in
     * @param smithyFile Smithy file the hover is in
     * @param minimumSeverity Minimum severity of validation events to show
     */
    public HoverHandler(Project project, IdlFile smithyFile, Severity minimumSeverity) {
        this.project = project;
        this.smithyFile = smithyFile;
        this.minimumSeverity = minimumSeverity;
    }

    /**
     * @param params The request params
     * @return The hover content
     */
    public Hover handle(HoverParams params) {
        Position position = params.getPosition();
        DocumentId id = smithyFile.document().copyDocumentId(position);
        if (id == null || id.idSlice().isEmpty()) {
            return EMPTY;
        }

        Syntax.IdlParseResult parseResult = smithyFile.getParse();
        int documentIndex = smithyFile.document().indexOfPosition(position);
        IdlPosition idlPosition = StatementView.createAt(parseResult, documentIndex)
                .map(IdlPosition::of)
                .orElse(null);

        return switch (idlPosition) {
            case IdlPosition.ControlKey ignored -> Builtins.CONTROL.getMember(id.copyIdValueForElidedMember())
                    .flatMap(HoverHandler::withBuiltinShapeDocs)
                    .orElse(EMPTY);

            case IdlPosition.MetadataKey ignored -> Builtins.METADATA.getMember(id.copyIdValue())
                    .flatMap(HoverHandler::withBuiltinShapeDocs)
                    .orElse(EMPTY);

            case IdlPosition.MetadataValue metadataValue -> takeShapeReference(
                            ShapeSearch.searchMetadataValue(metadataValue))
                    .flatMap(HoverHandler::withBuiltinShapeDocs)
                    .orElse(EMPTY);

            case IdlPosition.StatementKeyword ignored -> Builtins.SHAPE_MEMBER_TARGETS.getMember(id.copyIdValue())
                    .or(() -> Builtins.NON_SHAPE_KEYWORDS.getMember(id.copyIdValue()))
                    .flatMap(HoverHandler::withBuiltinShapeDocs)
                    .orElse(EMPTY);

            case IdlPosition.MemberName memberName -> getBuiltinMember(memberName)
                    .flatMap(HoverHandler::withBuiltinShapeDocs)
                    // Fall back to user model hover, since we didn't find a matching builtin shape with docs
                    .orElseGet(() -> modelSensitiveHover(id, memberName));

            case null -> EMPTY;

            default -> modelSensitiveHover(id, idlPosition);
        };
    }

    private static Optional<? extends Shape> takeShapeReference(NodeSearch.Result result) {
        return switch (result) {
            case NodeSearch.Result.TerminalShape(Shape shape, var ignored)
                    when shape.hasTrait(IdRefTrait.class) -> Optional.of(shape);

            case NodeSearch.Result.ObjectKey(NodeCursor.Key key, Shape containerShape, var ignored)
                    when !containerShape.isMapShape() -> containerShape.getMember(key.name());

            default -> Optional.empty();
        };
    }

    private static Optional<MemberShape> getBuiltinMember(IdlPosition.MemberName memberName) {
        var shapeDef = memberName.view().nearestShapeDefBefore();
        if (shapeDef == null) {
            return Optional.empty();
        }

        String shapeType = shapeDef.shapeType().stringValue();
        StructureShape shapeMembersDef = Builtins.getMembersForShapeType(shapeType);
        if (shapeMembersDef == null) {
            return Optional.empty();
        }

        return shapeMembersDef.getMember(memberName.name());
    }

    private Hover modelSensitiveHover(DocumentId id, IdlPosition idlPosition) {
        ValidatedResult<Model> validatedModel = project.modelResult();
        if (validatedModel.getResult().isEmpty()) {
            return EMPTY;
        }

        Model model = validatedModel.getResult().get();
        Optional<? extends Shape> matchingShape = switch (idlPosition) {
            // TODO: Handle resource ids and properties. This only works for mixins right now.
            case IdlPosition.ElidedMember elidedMember ->
                    ShapeSearch.findElidedMemberParent(elidedMember, id, model)
                            .flatMap(shape -> shape.getMember(id.copyIdValueForElidedMember()));

            default -> ShapeSearch.findShapeDefinition(idlPosition, id, model);
        };

        if (matchingShape.isEmpty()) {
            return EMPTY;
        }

        return withShapeAndValidationEvents(matchingShape.get(), model, validatedModel.getValidationEvents());
    }

    private Hover withShapeAndValidationEvents(Shape shape, Model model, List<ValidationEvent> events) {
        String serializedShape = switch (shape) {
            case MemberShape memberShape -> serializeMember(memberShape);
            default -> serializeShape(model, shape);
        };

        if (serializedShape == null) {
            return EMPTY;
        }

        String serializedValidationEvents = serializeValidationEvents(events, shape);

        String hoverContent = String.format("""
                %s
                ```smithy
                %s
                ```
                """, serializedValidationEvents, serializedShape);

        // TODO: Add docs to a separate section of the hover content
        // if (shapeToSerialize.hasTrait(DocumentationTrait.class)) {
        //     String docs = shapeToSerialize.expectTrait(DocumentationTrait.class).getValue();
        //     hoverContent.append("\n---\n").append(docs);
        // }

        return withMarkupContents(hoverContent);
    }

    private String serializeValidationEvents(List<ValidationEvent> events, Shape shape) {
        StringBuilder serialized = new StringBuilder();
        List<ValidationEvent> applicableEvents = events.stream()
                .filter(event -> event.getShapeId().isPresent())
                .filter(event -> event.getShapeId().get().equals(shape.getId()))
                .filter(event -> event.getSeverity().compareTo(minimumSeverity) >= 0)
                .toList();

        if (!applicableEvents.isEmpty()) {
            for (ValidationEvent event : applicableEvents) {
                serialized.append("**")
                        .append(event.getSeverity())
                        .append("**")
                        .append(": ")
                        .append(event.getMessage());
            }
            serialized.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("---")
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        return serialized.toString();
    }

    // Note: This isn't used for user-defined shapes because we include docs
    // in the serialized hover content.
    static Optional<Hover> withBuiltinShapeDocs(Shape shape) {
        StringBuilder builder = new StringBuilder();

        var builtinShapeDocs = BuiltinShapeDocs.forShape(shape);

        if (!builtinShapeDocs.shapeDocs.isEmpty()) {
            builder.append(builtinShapeDocs.shapeDocs);

            if (!builtinShapeDocs.externalDocs.isEmpty()) {
                // Space out regular docs and external docs so they're easier to read.
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }
        }

        builtinShapeDocs.externalDocs
                .forEach((url, doc) -> builder.append(String.format("[%s](%s)%n", url, doc)));

        if (builder.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Hover(new MarkupContent("markdown", builder.toString())));
    }

    private record BuiltinShapeDocs(String shapeDocs, Map<String, String> externalDocs) {
        private static BuiltinShapeDocs forShape(Shape shape) {
            var shapeDocs = shape.getTrait(DocumentationTrait.class)
                    .map(DocumentationTrait::getValue)
                    .orElse("");

            Map<String, String> externalDocs = new HashMap<>();

            shape.getTrait(ExternalDocumentationTrait.class)
                    .map(ExternalDocumentationTrait::getUrls)
                    .ifPresent(externalDocs::putAll);

            // The builtins model defines some external docs on root shapes, which are meant to be
            // included in the hover content for all members so we can always provide a link to
            // Smithy's docs, even if the member itself doesn't have a specific link that would
            // make sense.
            shape.asMemberShape()
                    .map(MemberShape::getContainer)
                    .flatMap(Builtins.MODEL::getShape)
                    .flatMap(container -> container.getTrait(ExternalDocumentationTrait.class))
                    .map(ExternalDocumentationTrait::getUrls)
                    .ifPresent(externalDocs::putAll);

            return new BuiltinShapeDocs(shapeDocs, externalDocs);
        }
    }

    private static Hover withMarkupContents(String text) {
        return new Hover(new MarkupContent("markdown", text));
    }

    private static String serializeMember(MemberShape memberShape) {
        StringBuilder contents = new StringBuilder();
        contents.append("namespace")
                .append(" ")
                .append(memberShape.getId().getNamespace())
                .append(System.lineSeparator())
                .append(System.lineSeparator());

        memberShape.getTrait(DocumentationTrait.class)
                .map(DocumentationTrait::getValue)
                .ifPresent(docs -> addMemberDocs(contents, docs));

        for (var trait : memberShape.getAllTraits().values()) {
            if (trait.toShapeId().equals(DocumentationTrait.ID)) {
                continue;
            }

            contents.append("@")
                    .append(trait.toShapeId().getName())
                    .append("(")
                    .append(Node.printJson(trait.toNode()))
                    .append(")")
                    .append(System.lineSeparator());
        }

        contents.append(memberShape.getMemberName())
                .append(": ")
                .append(memberShape.getTarget().getName())
                .append(System.lineSeparator());
        return contents.toString();
    }

    private static void addMemberDocs(StringBuilder builder, String docs) {
        builder.append("/// ")
                // Replace newline literals in the doc string with actual newlines, and /// so we can render
                // an IDL doc comment.
                .append(docs.replaceAll(
                                Matcher.quoteReplacement(System.lineSeparator()), System.lineSeparator() + "/// ")
                        .trim())
                .append(System.lineSeparator());

    }

    private static String serializeShape(Model model, Shape shape) {
        SmithyIdlModelSerializer serializer = SmithyIdlModelSerializer.builder()
                .metadataFilter(key -> false)
                .shapeFilter(s -> s.getId().equals(shape.getId()))
                // TODO: If we remove the documentation trait in the serializer,
                //  it also gets removed from members. This causes weird behavior if
                //  there are applied traits (such as through mixins), where you get
                //  an empty apply because the documentation trait was removed
                // .traitFilter(trait -> !trait.toShapeId().equals(DocumentationTrait.ID))
                .serializePrelude()
                .build();
        Map<Path, String> serialized = serializer.serialize(model);
        Path path = Paths.get(shape.getId().getNamespace() + ".smithy");
        if (!serialized.containsKey(path)) {
            return null;
        }

        String serializedShape = serialized.get(path)
                .substring(15) // remove '$version: "2.0"'
                .trim()
                .replaceAll(Matcher.quoteReplacement(
                        // Replace newline literals with actual newlines
                        System.lineSeparator() + System.lineSeparator()), System.lineSeparator());
        return serializedShape;
    }
}
