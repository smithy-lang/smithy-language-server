/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import static java.util.regex.Matcher.quoteReplacement;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentPositionContext;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.SmithyIdlModelSerializer;
import software.amazon.smithy.model.traits.MixinTrait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public final class HoverHandler {
    private final Project project;

    public HoverHandler(Project project) {
        this.project = project;
    }

    /**
     * @param params The request params
     * @param minimumSeverity The minimum severity of events to show
     * @return The hover content
     */
    public Hover handle(HoverParams params, Severity minimumSeverity) {
        Hover hover = new Hover();
        hover.setContents(new MarkupContent("markdown", ""));
        String uri = params.getTextDocument().getUri();
        SmithyFile smithyFile = project.getSmithyFile(uri);
        if (smithyFile == null) {
            return hover;
        }

        Position position = params.getPosition();
        DocumentId id = smithyFile.getDocument().getDocumentIdAt(position);
        if (id == null || id.borrowIdValue().length() == 0) {
            return hover;
        }

        ValidatedResult<Model> modelResult = project.getModelResult();
        if (!modelResult.getResult().isPresent()) {
            return hover;
        }

        Model model = modelResult.getResult().get();
        DocumentPositionContext context = DocumentParser.forDocument(smithyFile.getDocument())
                .determineContext(position);
        Optional<Shape> matchingShape = contextualShapes(model, context)
                .filter(contextualMatcher(smithyFile, id))
                .findFirst();

        if (!matchingShape.isPresent()) {
            return hover;
        }

        Shape shapeToSerialize = matchingShape.get();

        SmithyIdlModelSerializer serializer = SmithyIdlModelSerializer.builder()
                .metadataFilter(key -> false)
                .shapeFilter(s -> s.getId().equals(shapeToSerialize.getId()))
                // TODO: If we remove the documentation trait in the serializer,
                //  it also gets removed from members. This causes weird behavior if
                //  there are applied traits (such as through mixins), where you get
                //  an empty apply because the documentation trait was removed
                // .traitFilter(trait -> !trait.toShapeId().equals(DocumentationTrait.ID))
                .serializePrelude()
                .build();
        Map<Path, String> serialized = serializer.serialize(model);
        Path path = Paths.get(shapeToSerialize.getId().getNamespace() + ".smithy");
        if (!serialized.containsKey(path)) {
            return hover;
        }

        StringBuilder hoverContent = new StringBuilder();
        List<ValidationEvent> validationEvents = modelResult.getValidationEvents().stream()
                .filter(event -> event.getShapeId().isPresent())
                .filter(event -> event.getShapeId().get().equals(shapeToSerialize.getId()))
                .filter(event -> event.getSeverity().compareTo(minimumSeverity) >= 0)
                .collect(Collectors.toList());
        if (!validationEvents.isEmpty()) {
            for (ValidationEvent event : validationEvents) {
                hoverContent.append("**")
                        .append(event.getSeverity())
                        .append("**")
                        .append(": ")
                        .append(event.getMessage());
            }
            hoverContent.append("\n\n---\n\n");
        }

        String serializedShape = serialized.get(path)
                .substring(15) // remove '$version: "2.0"'
                .trim()
                .replaceAll(quoteReplacement("\n\n"), "\n");
        int eol = serializedShape.indexOf('\n');
        String namespaceLine = serializedShape.substring(0, eol);
        serializedShape = serializedShape.substring(eol + 1);
        hoverContent.append(String.format("```smithy\n"
                                          + "%s\n"
                                          + "%s\n"
                                          + "```\n", namespaceLine, serializedShape));

        // TODO: Add docs to a separate section of the hover content
        // if (shapeToSerialize.hasTrait(DocumentationTrait.class)) {
        //     String docs = shapeToSerialize.expectTrait(DocumentationTrait.class).getValue();
        //     hoverContent.append("\n---\n").append(docs);
        // }

        MarkupContent content = new MarkupContent("markdown", hoverContent.toString());
        hover.setContents(content);
        return hover;
    }

    private static Predicate<Shape> contextualMatcher(SmithyFile smithyFile, DocumentId id) {
        String token = id.copyIdValue();
        if (id.getType() == DocumentId.Type.ABSOLUTE_ID) {
            return (shape) -> shape.getId().toString().equals(token);
        } else {
            return (shape) -> (Prelude.isPublicPreludeShape(shape)
                               || shape.getId().getNamespace().contentEquals(smithyFile.getNamespace())
                               || smithyFile.hasImport(shape.getId().toString()))
                              && shape.getId().getName().equals(token);
        }
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
                return model.shapes().filter(shape -> !shape.isMemberShape());
        }
    }
}
