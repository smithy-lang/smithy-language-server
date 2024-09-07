/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentPositionContext;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.MixinTrait;
import software.amazon.smithy.model.traits.TraitDefinition;

/**
 * Handles go-to-definition requests.
 */
public final class DefinitionHandler {
    private final Project project;
    private final SmithyFile smithyFile;

    public DefinitionHandler(Project project, SmithyFile smithyFile) {
        this.project = project;
        this.smithyFile = smithyFile;
    }

    /**
     * @param params The request params
     * @return A list of possible definition locations
     */
    public List<Location> handle(DefinitionParams params) {
        Position position = params.getPosition();
        DocumentId id = smithyFile.document().copyDocumentId(position);
        if (id == null || id.idSlice().isEmpty()) {
            return Collections.emptyList();
        }

        Optional<Model> modelResult = project.modelResult().getResult();
        if (modelResult.isEmpty()) {
            return Collections.emptyList();
        }

        Model model = modelResult.get();
        DocumentPositionContext context = DocumentParser.forDocument(smithyFile.document())
                .determineContext(position);
        return contextualShapes(model, context)
                .filter(contextualMatcher(smithyFile, id))
                .findFirst()
                .map(Shape::getSourceLocation)
                .map(LspAdapter::toLocation)
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());
    }

    private static Predicate<Shape> contextualMatcher(SmithyFile smithyFile, DocumentId id) {
        String token = id.copyIdValue();
        if (id.type() == DocumentId.Type.ABSOLUTE_ID) {
            return (shape) -> shape.getId().toString().equals(token);
        } else {
            return (shape) -> (Prelude.isPublicPreludeShape(shape)
                               || shape.getId().getNamespace().contentEquals(smithyFile.namespace())
                               || smithyFile.hasImport(shape.getId().toString()))
                              && shape.getId().getName().equals(token);
        }
    }

    private static Stream<Shape> contextualShapes(Model model, DocumentPositionContext context) {
        return switch (context) {
            case TRAIT -> model.getShapesWithTrait(TraitDefinition.class).stream();
            case MEMBER_TARGET -> model.shapes()
                    .filter(shape -> !shape.isMemberShape())
                    .filter(shape -> !shape.hasTrait(TraitDefinition.class));
            case MIXIN -> model.getShapesWithTrait(MixinTrait.class).stream();
            default -> model.shapes().filter(shape -> !shape.isMemberShape());
        };
    }
}
