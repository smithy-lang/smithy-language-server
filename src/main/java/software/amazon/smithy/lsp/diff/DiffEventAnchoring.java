/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Rewrites diff {@link ValidationEvent}s so that every event points at a file the user is
 * currently editing, ready to be merged into per-file diagnostics
 *
 * <p>An LSP {@code Diagnostic} needs a {@code (uri, range)}. Events about shapes still present in
 * the new model already carry a {@code sourceLocation} in a current file and pass through
 * unchanged ("anchored"). Events about removals — {@code RemovedShape}, removed members, removed
 * metadata — point into the baseline, which the editor cannot open ("unanchored"). Those are
 * re-anchored:
 *
 * <ol>
 *   <li>by namespace: to a current file that defines the event's shape id's namespace, at the
 *       top of the file ({@code 1:1}, i.e. LSP {@code 0:0});</li>
 *   <li>otherwise (namespace fully removed, or the event has no shape id): to the project's
 *       build file at {@code 1:1}.</li>
 * </ol>
 *
 * <p>Because anchored events keep their original source location, downstream conversion in
 * {@code SmithyDiagnostics} (per-file + minimum-severity filtering, severity mapping, and IDL
 * range refinement) applies to diff events uniformly.
 */
public final class DiffEventAnchoring {

    public static final int ANCHOR_LINE = 1;
    public static final int ANCHOR_COLUMN = 1;

    private DiffEventAnchoring() {
    }

    public static boolean isAnchoredToOrigin(ValidationEvent event) {
        SourceLocation location = event.getSourceLocation();
        return location.getLine() == ANCHOR_LINE && location.getColumn() == ANCHOR_COLUMN;
    }

    /**
     * Re-anchors unanchored diff events to current files.
     *
     * @param diffEvents the raw diff events
     * @param currentModel the current (edited) model, used to locate namespaces' files
     * @param buildFilePath fallback file path for events that can't be anchored by namespace;
     *                      may be {@code null}, in which case such events are left unchanged
     * @return the events with unanchored ones rewritten to point at current files
     */
    public static List<ValidationEvent> anchor(
            List<ValidationEvent> diffEvents,
            Model currentModel,
            String buildFilePath
    ) {
        FileIndex index = indexCurrentFiles(currentModel);

        return diffEvents.stream()
                .map(event -> anchorEvent(event, index.currentFiles(), index.namespaceToFile(), buildFilePath))
                .toList();
    }

    private static ValidationEvent anchorEvent(
            ValidationEvent event,
            Set<String> currentFiles,
            Map<String, String> namespaceToFile,
            String buildFilePath
    ) {
        String filename = event.getSourceLocation().getFilename();
        if (currentFiles.contains(filename)) {
            return event;
        }

        String targetFile = event.getShapeId()
                .map(shapeId -> namespaceToFile.get(shapeId.getNamespace()))
                .orElse(buildFilePath);
        if (targetFile == null) {
            return event;
        }

        return event.toBuilder()
                .sourceLocation(new SourceLocation(targetFile, ANCHOR_LINE, ANCHOR_COLUMN))
                .build();
    }

    // The set of current editable files and a map from each namespace to a deterministic
    // (lexicographically smallest) current file defining a shape in it, built in a single pass.
    private record FileIndex(Set<String> currentFiles, Map<String, String> namespaceToFile) {
    }

    private static FileIndex indexCurrentFiles(Model model) {
        Set<String> currentFiles = new HashSet<>();
        Map<String, String> namespaceToFile = new HashMap<>();
        model.shapes().forEach(shape -> {
            String filename = shape.getSourceLocation().getFilename();
            if (isEditableFile(filename)) {
                currentFiles.add(filename);
                // Namespaces spanning multiple files anchor stably to the smallest filename.
                namespaceToFile.merge(
                        shape.getId().getNamespace(),
                        filename,
                        (existing, candidate) -> existing.compareTo(candidate) <= 0 ? existing : candidate);
            }
        });
        return new FileIndex(currentFiles, namespaceToFile);
    }

    private static boolean isEditableFile(String filename) {
        return !filename.equals(SourceLocation.NONE.getFilename())
                && !LspAdapter.isJarFile(filename)
                && !LspAdapter.isSmithyJarFile(filename);
    }
}
