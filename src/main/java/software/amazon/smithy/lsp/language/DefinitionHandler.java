/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.Model;

/**
 * Handles go-to-definition requests for the Smithy IDL.
 */
public final class DefinitionHandler {
    final Project project;
    final IdlFile smithyFile;

    public DefinitionHandler(Project project, IdlFile smithyFile) {
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
        Syntax.IdlParseResult parseResult = smithyFile.getParse();
        int documentIndex = smithyFile.document().indexOfPosition(position);
        return StatementView.createAt(parseResult, documentIndex)
                .map(IdlPosition::of)
                .flatMap(idlPosition -> ShapeSearch.findShapeDefinition(idlPosition, id, model))
                .map(LspAdapter::toLocation)
                .map(List::of)
                .orElse(List.of());
    }
}
