/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidatedResult;

/**
 * An index that caches rebuild dependency relationships between Smithy files,
 * shapes, and traits.
 *
 * <p>This is specifically for the following scenarios:
 * <dl>
 *   <dt>A file applies traits to shapes in other files</dt>
 *   <dd>If that file changes, the applied traits need to be removed before the
 *   file is reloaded, so there aren't duplicate traits.</dd>
 *   <dt>A file has shapes with traits applied in other files</dt>
 *   <dd>If that file changes, the traits need to be re-applied when the model is
 *   re-assembled, so they aren't lost.</dd>
 *   <dt>Either 1 or 2, but specifically with list traits</dt>
 *   <dd>List traits are merged via <a href="https://smithy.io/2.0/spec/model.html#trait-conflict-resolution">
 *   trait conflict resolution </a>. For these traits, all files that contain
 *   parts of the list trait must be fully reloaded, since we can only remove
 *   the whole trait, not parts of it.</dd>
 * </dl>
 */
final class SmithyFileDependenciesIndex {
    private final Map<String, Set<String>> filesToDependentFiles;
    private final Map<ShapeId, Set<String>> shapeIdsToDependenciesFiles;
    private final Map<String, Map<ShapeId, List<Trait>>> filesToTraitsTheyApply;
    private final Map<ShapeId, List<Trait>> shapesToAppliedTraitsInOtherFiles;

    SmithyFileDependenciesIndex() {
        this.filesToDependentFiles = new HashMap<>(0);
        this.shapeIdsToDependenciesFiles = new HashMap<>(0);
        this.filesToTraitsTheyApply = new HashMap<>(0);
        this.shapesToAppliedTraitsInOtherFiles = new HashMap<>(0);
    }

    private SmithyFileDependenciesIndex(
            Map<String, Set<String>> filesToDependentFiles,
            Map<ShapeId, Set<String>> shapeIdsToDependenciesFiles,
            Map<String, Map<ShapeId, List<Trait>>> filesToTraitsTheyApply,
            Map<ShapeId, List<Trait>> shapesToAppliedTraitsInOtherFiles
    ) {
        this.filesToDependentFiles = filesToDependentFiles;
        this.shapeIdsToDependenciesFiles = shapeIdsToDependenciesFiles;
        this.filesToTraitsTheyApply = filesToTraitsTheyApply;
        this.shapesToAppliedTraitsInOtherFiles = shapesToAppliedTraitsInOtherFiles;
    }

    Set<String> getDependentFiles(String path) {
        return filesToDependentFiles.getOrDefault(path, Collections.emptySet());
    }

    Set<String> getDependenciesFiles(ToShapeId toShapeId) {
        return shapeIdsToDependenciesFiles.getOrDefault(toShapeId.toShapeId(), Collections.emptySet());
    }

    Map<ShapeId, List<Trait>> getAppliedTraitsInFile(String path) {
        return filesToTraitsTheyApply.getOrDefault(path, Collections.emptyMap());
    }

    List<Trait> getTraitsAppliedInOtherFiles(ToShapeId toShapeId) {
        return shapesToAppliedTraitsInOtherFiles.getOrDefault(toShapeId.toShapeId(), Collections.emptyList());
    }

    // TODO: Make this take care of metadata too
    static SmithyFileDependenciesIndex compute(ValidatedResult<Model> modelResult) {
        if (!modelResult.getResult().isPresent()) {
            return new SmithyFileDependenciesIndex();
        }

        SmithyFileDependenciesIndex index = new SmithyFileDependenciesIndex(
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());

        Model model = modelResult.getResult().get();
        for (Shape shape : model.toSet()) {
            String shapeSourceFilename = shape.getSourceLocation().getFilename();
            for (Trait traitApplication : shape.getAllTraits().values()) {
                // We only care about trait applications in the source files
                if (traitApplication.isSynthetic()) {
                    continue;
                }

                Node traitNode = traitApplication.toNode();
                if (traitNode.isArrayNode()) {
                    for (Node element : traitNode.expectArrayNode()) {
                        String elementSourceFilename = element.getSourceLocation().getFilename();
                        if (!elementSourceFilename.equals(shapeSourceFilename)) {
                            index.filesToDependentFiles.computeIfAbsent(elementSourceFilename, (k) -> new HashSet<>())
                                    .add(shapeSourceFilename);
                            index.shapeIdsToDependenciesFiles.computeIfAbsent(shape.getId(), (k) -> new HashSet<>())
                                    .add(elementSourceFilename);
                        }
                    }
                } else {
                    String traitSourceFilename = traitApplication.getSourceLocation().getFilename();
                    if (!traitSourceFilename.equals(shapeSourceFilename)) {
                        index.shapesToAppliedTraitsInOtherFiles.computeIfAbsent(shape.getId(), (k) -> new ArrayList<>())
                                .add(traitApplication);
                        index.filesToTraitsTheyApply.computeIfAbsent(traitSourceFilename, (k) -> new HashMap<>())
                                .computeIfAbsent(shape.getId(), (k) -> new ArrayList<>())
                                .add(traitApplication);
                    }
                }
            }
        }

        return index;
    }
}
