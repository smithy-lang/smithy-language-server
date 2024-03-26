/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.handler;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentPositionContext;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LocationAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.MixinTrait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.model.validation.ValidatedResult;

public final class DefinitionHandler {
    private final Project project;

    public DefinitionHandler(Project project) {
        this.project = project;
    }

    /**
     * @param params The request params
     * @return A list of possible definition locations
     */
    public List<Location> handle(DefinitionParams params) {
        String uri = params.getTextDocument().getUri();
        SmithyFile smithyFile = project.getSmithyFile(uri);
        if (smithyFile == null) {
            return Collections.emptyList();
        }

        Position position = params.getPosition();
        String token = smithyFile.getDocument().copyId(position);
        if (token == null) {
            return Collections.emptyList();
        }

        ValidatedResult<Model> modelResult = project.getModelResult();
        if (!modelResult.getResult().isPresent()) {
            return Collections.emptyList();
        }

        Model model = modelResult.getResult().get();
        Set<String> imports = smithyFile.getImports();
        CharSequence namespace = smithyFile.getNamespace();
        DocumentPositionContext context = DocumentParser.forDocument(smithyFile.getDocument())
                .determineContext(position);
        return contextualShapes(model, context)
                .filter(shape -> Prelude.isPublicPreludeShape(shape)
                        || shape.getId().getNamespace().contentEquals(namespace)
                        || imports.contains(shape.getId().toString()))
                .filter(shape -> shape.getId().getName().equals(token))
                .findFirst()
                .map(Shape::getSourceLocation)
                .map(LocationAdapter::fromSource)
                .map(Collections::singletonList)
                .orElse(Collections.emptyList());
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
