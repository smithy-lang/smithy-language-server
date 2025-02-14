/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Optional;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.syntax.NodeCursor;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.ExternalDocumentationTrait;

/**
 * Handles hover requests for build files.
 */
public final class BuildHoverHandler {
    private final BuildFile buildFile;

    public BuildHoverHandler(BuildFile buildFile) {
        this.buildFile = buildFile;
    }

    /**
     * @param params The request params
     * @return The hover content
     */
    public Hover handle(HoverParams params) {
        Shape buildFileShape = Builtins.getBuildFileShape(buildFile.type());

        if (buildFileShape == null) {
            return null;
        }

        Position position = params.getPosition();
        NodeCursor cursor = NodeCursor.create(
                buildFile.getParse().value(),
                buildFile.document().indexOfPosition(position)
        );
        NodeSearch.Result searchResult = NodeSearch.search(cursor, Builtins.MODEL, buildFileShape);

        return getMemberShape(searchResult)
                .map(BuildHoverHandler::withShapeDocs)
                .orElse(null);
    }

    private static Optional<MemberShape> getMemberShape(NodeSearch.Result searchResult) {
        // We only provide hover on properties (json keys). Otherwise, the hover content could
        // be noisy if your cursor is just sitting somewhere.
        if (searchResult instanceof NodeSearch.Result.ObjectKey objectKey) {
            if (!objectKey.containerShape().isMapShape()) {
                return objectKey.containerShape().getMember(objectKey.key().name());
            }
        }

        return Optional.empty();
    }

    private static Hover withShapeDocs(MemberShape memberShape) {
        StringBuilder builder = new StringBuilder();

        var docs = memberShape.getTrait(DocumentationTrait.class).orElse(null);
        var externalDocs = memberShape.getTrait(ExternalDocumentationTrait.class).orElse(null);

        if (docs != null) {
            builder.append(docs.getValue());
        }

        if (externalDocs != null) {
            if (docs != null) {
                // Add some extra space between regular docs and external
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }

            externalDocs.getUrls()
                    .forEach((name, url) -> builder.append(String.format("[%s](%s)%n", name, url)));
        }

        if (builder.isEmpty()) {
            return null;
        }

        return new Hover(new MarkupContent("markdown", builder.toString()));
    }
}
