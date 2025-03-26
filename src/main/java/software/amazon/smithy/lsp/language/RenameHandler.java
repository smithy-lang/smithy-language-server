/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeIdSyntaxException;

public record RenameHandler(Project project, IdlFile idlFile) {
    /**
     * @param params The request params
     * @return The range of the identifier to rename
     */
    public Range prepare(PrepareRenameParams params) {
        var config = ReferencesHandler.Config.create(project, idlFile, params.getPosition());
        return getRenameRange(config.id().range(), config.id().copyIdValue());
    }

    /**
     * @param params The request params
     * @return A workspace edit that applies the rename
     */
    public WorkspaceEdit handle(RenameParams params) {
        var config = ReferencesHandler.Config.create(project, idlFile, params.getPosition());
        var edits = getEdits(config, params.getNewName());
        return new WorkspaceEdit(edits);
    }

    private Map<String, List<TextEdit>> getEdits(ReferencesHandler.Config config, String newName) {
        String namespace = config.shape().getId().getNamespace();

        ShapeId renamedId;
        try {
            renamedId = ShapeId.fromRelative(namespace, newName);
        } catch (ShapeIdSyntaxException e) {
            throw invalidShapeId(e);
        }

        var projectEdits = new ProjectEdits(config, newName, renamedId, new HashMap<>());
        projectEdits.collect(project);

        return projectEdits.edits;
    }

    private record ProjectEdits(
            ReferencesHandler.Config config,
            String newName,
            ShapeId renamedShapeId,
            Map<String, List<TextEdit>> edits
    ) {
        private enum FileEditType {
            CONFLICT,
            SIMPLE
        }

        private void collect(Project project) {
            var references = References.findReferences(config.model(), config.shape(), project);

            addEdits(references);
            deconflictDefinition();
        }

        private void addEdits(References allReferences) {
            for (var fileReferences : allReferences.fileReferences()) {
                FileEditType fileEditType = getEditType(fileReferences.idlFile());
                if (fileEditType == FileEditType.CONFLICT) {
                    addConflictRenames(fileReferences, renamedShapeId.toString());
                } else {
                    addSimpleRenames(fileReferences);
                }
            }

            for (var definitionReference : allReferences.definitionReferences()) {
                var uri = checkIfJar(definitionReference.idlFile());

                addSimpleRename(uri, definitionReference.idlFile(), definitionReference.ref());
            }
        }

        private void deconflictDefinition() {
            var sourceFile = config.definitionFile();
            if (sourceFile == null) {
                return;
            }

            String conflictingId = getConflictingImport(sourceFile, newName);
            if (conflictingId == null) {
                return;
            }

            var conflictingShape = ShapeSearch.findShape(sourceFile.getParse(), conflictingId, config.model())
                    .orElse(null);
            if (conflictingShape == null) {
                return;
            }

            var references = References.findReferences(config.model(), conflictingShape, sourceFile);
            for (var fileReferences : references.fileReferences()) {
                addConflictRenames(fileReferences, conflictingId);
            }
            // Note: No deconflict needed for the definition (plus it won't be picked up by allReferences)
        }

        private void addConflictRenames(References.FileReferences fileReferences, String conflictingId) {
            var uri = checkIfJar(fileReferences.idlFile());

            for (var ref : fileReferences.refs()) {
                var range = fileReferences.idlFile().document().rangeOfValue(ref);
                if (range == null) {
                    continue;
                }

                var referenceId = ref.stringValue();
                var renamedId = conflictingId;
                if (referenceId.contains("$")) {
                    renamedId = conflictingId + "$" + referenceId.split("\\$")[1];
                }

                add(uri, range, renamedId);
            }

            for (var use : fileReferences.useRefs()) {
                var range = fileReferences.idlFile().document().rangeOf(use);
                if (range == null) {
                    continue;
                }

                add(uri, range, "");
            }
        }

        private void addSimpleRenames(References.FileReferences fileReferences) {
            var uri = checkIfJar(fileReferences.idlFile());

            for (var ref : fileReferences.refs()) {
                addSimpleRename(uri, fileReferences.idlFile(), ref);
            }

            for (var use : fileReferences.useRefs()) {
                var range = fileReferences.idlFile().document().rangeOfValue(use.use());
                if (range == null) {
                    continue;
                }

                var referenceId = use.use().stringValue();
                var renameRange = getRenameRange(range, referenceId);
                add(uri, renameRange, newName);
            }
        }

        private void addSimpleRename(String uri, IdlFile idlFile, Syntax.Node.Str ref) {
            var range = idlFile.document().rangeOfValue(ref);
            if (range == null) {
                return;
            }

            var referenceId = ref.stringValue();
            var renameRange = getRenameRange(range, referenceId);
            add(uri, renameRange, newName);
        }

        private String checkIfJar(IdlFile idlFile) {
            String uri = LspAdapter.toUri(idlFile.path());
            if (!LspAdapter.isJarFile(uri) && !LspAdapter.isSmithyJarFile(uri)) {
                return uri;
            }

            throw referencedInJar(uri);
        }

        private FileEditType getEditType(IdlFile idlFile) {
            if (isDefinitionFile(idlFile) || !conflicts(idlFile)) {
                return FileEditType.SIMPLE;
            } else {
                return FileEditType.CONFLICT;
            }
        }

        private boolean isDefinitionFile(IdlFile idlFile) {
            return config.definitionFile() != null && config.definitionFile().path().equals(idlFile.path());
        }

        private boolean conflicts(IdlFile idlFile) {
            if (renamedShapeId == null) {
                return false;
            }

            String fileNamespace = idlFile.getParse().namespace().namespace();
            if (!renamedShapeId.getNamespace().equals(fileNamespace)) {
                ShapeId renamedCurrentScope = ShapeId.fromRelative(fileNamespace, newName);
                if (config.model().getShape(renamedCurrentScope).isPresent()) {
                    return true;
                }
            }

            return getConflictingImport(idlFile, newName) != null;
        }

        private void add(String uri, Range renamedRange, String renamed) {
            var edit = new TextEdit(renamedRange, renamed);
            edits.computeIfAbsent(uri, k -> new ArrayList<>()).add(edit);
        }
    }

    private static ResponseErrorException invalidShapeId(ShapeIdSyntaxException e) {
        var responseError = new ResponseError();
        responseError.setCode(ResponseErrorCode.RequestFailed);
        responseError.setMessage("Renamed shape id would be invalid: " + e.getMessage());
        return new ResponseErrorException(responseError);
    }

    private static ResponseErrorException referencedInJar(String uri) {
        var error = new ResponseError();
        error.setCode(ResponseErrorCode.RequestFailed);
        error.setMessage("Can't rename shape referenced in jar: " + uri);
        return new ResponseErrorException(error);
    }

    private static Range getRenameRange(Range range, String idString) {
        int originalStartCharacter = range.getStart().getCharacter();
        int hashIdx = idString.indexOf('#');
        if (hashIdx >= 0) {
            int currentCharacter = range.getStart().getCharacter();
            range.getStart().setCharacter(currentCharacter + hashIdx + 1);
        }
        int dollarIdx = idString.indexOf('$');
        if (dollarIdx >= 0) {
            range.getEnd().setCharacter(originalStartCharacter + dollarIdx);
        }
        return range;
    }

    private static String getConflictingImport(IdlFile idlFile, String newName) {
        String matcher = "#" + newName;
        for (String imported : idlFile.getParse().imports().imports()) {
            if (imported.endsWith(matcher)) {
                return imported;
            }
        }
        return null;
    }
}
