/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import software.amazon.smithy.lsp.document.DocumentId;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;

public record ReferencesHandler(Project project, IdlFile idlFile) {
    /**
     * @param params The request params
     * @return A list of locations of the found refs
     */
    public List<? extends Location> handle(ReferenceParams params) {
        var config = Config.create(project, idlFile, params.getPosition());
        var references = References.findReferences(config.model(), config.shape(), project);
        return toLocations(references);
    }

    record Config(DocumentId id, Shape shape, Model model, IdlFile definitionFile) {
        static Config create(Project project, IdlFile idlFile, Position position) {
            DocumentId id = idlFile.document().copyDocumentId(position);
            if (id == null || id.idSlice().isEmpty()) {
                throw notSupported();
            }

            var parseResult = idlFile.getParse();
            int documentIndex = idlFile.document().indexOfPosition(position);
            var idlPosition = StatementView.createAt(parseResult, documentIndex)
                    .map(IdlPosition::of)
                    .orElse(null);
            if (idlPosition == null) {
                throw notSupported();
            }

            var model = project.modelResult().getResult().orElse(null);
            if (model == null) {
                throw noModel();
            }

            var shapeReference = ShapeSearch.getShapeReference(idlPosition, id, model);
            if (shapeReference.isEmpty()) {
                throw notSupported();
            }

            var shape = shapeReference.get();
            var definitionFile = project.getDefinitionFile(shape);

            IdlFile idlDefinitionFile = null;
            if (definitionFile instanceof IdlFile idl) {
                idlDefinitionFile = idl;
            }

            return new Config(id, shape, model, idlDefinitionFile);
        }

        private static ResponseErrorException notSupported() {
            var error = new ResponseError();
            error.setCode(ResponseErrorCode.RequestFailed);
            error.setMessage("Finding references not supported here.");
            return new ResponseErrorException(error);
        }

        private static ResponseErrorException noModel() {
            var error = new ResponseError();
            error.setCode(ResponseErrorCode.RequestFailed);
            error.setMessage("Model is too broken to find references.");
            return new ResponseErrorException(error);
        }
    }

    private List<Location> toLocations(References references) {
        List<Location> locations = new ArrayList<>();
        for (var fileReferences : references.fileReferences()) {
            String uri = LspAdapter.toUri(fileReferences.idlFile().path());

            for (var ref : fileReferences.refs()) {
                addLocation(locations, uri, fileReferences.idlFile().document().rangeOfValue(ref));
            }

            for (var use : fileReferences.useRefs()) {
                addLocation(locations, uri, fileReferences.idlFile().document().rangeOfValue(use.use()));
            }
        }

        for (var definitionRef : references.definitionReferences()) {
            String uri = LspAdapter.toUri(definitionRef.idlFile().path());
            addLocation(locations, uri, definitionRef.idlFile().document().rangeOfValue(definitionRef.ref()));
        }

        return locations;
    }

    private void addLocation(List<Location> locations, String uri, Range range) {
        if (range != null) {
            locations.add(new Location(uri, range));
        }
    }
}
