/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.AbstractShapeBuilder;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.IoUtils;

/**
 * A Smithy project open on the client. It keeps track of its Smithy files and
 * dependencies, and the currently loaded model.
 */
public final class Project {
    private static final Logger LOGGER = Logger.getLogger(Project.class.getName());

    private final Path root;
    private final ProjectConfig config;
    private final BuildFiles buildFiles;
    private final Map<String, SmithyFile> smithyFiles;
    private final Supplier<ModelAssembler> assemblerFactory;
    private final Type type;
    private volatile ValidatedResult<Model> modelResult;
    private volatile RebuildIndex rebuildIndex;
    private volatile List<ValidationEvent> configEvents;

    Project(
            Path root,
            ProjectConfig config,
            BuildFiles buildFiles,
            Map<String, SmithyFile> smithyFiles,
            Supplier<ModelAssembler> assemblerFactory,
            Type type,
            ValidatedResult<Model> modelResult,
            RebuildIndex rebuildIndex,
            List<ValidationEvent> configEvents
    ) {
        this.root = root;
        this.config = config;
        this.buildFiles = buildFiles;
        this.smithyFiles = smithyFiles;
        this.assemblerFactory = assemblerFactory;
        this.type = type;
        this.modelResult = modelResult;
        this.rebuildIndex = rebuildIndex;
        this.configEvents = configEvents;
    }

    /**
     * The type of project, which depends on how it was loaded.
     */
    public enum Type {
        /**
         * A project loaded using some build configuration files, i.e. smithy-build.json.
         */
        NORMAL,

        /**
         * A project loaded from a single source file, without any build configuration files.
         */
        DETACHED,

        /**
         * A project loaded with no source or build configuration files.
         */
        EMPTY;
    }

    /**
     * Create an empty project with no Smithy files, dependencies, or loaded model.
     *
     * @param root Root path of the project
     * @return The empty project
     */
    public static Project empty(Path root) {
        return new Project(root,
                ProjectConfig.empty(),
                BuildFiles.empty(),
                new HashMap<>(),
                Model::assembler,
                Type.EMPTY,
                ValidatedResult.empty(),
                new RebuildIndex(),
                List.of());
    }

    /**
     * @return The path of the root directory of the project
     */
    public Path root() {
        return root;
    }

    ProjectConfig config() {
        return config;
    }

    public List<ValidationEvent> configEvents() {
        return configEvents;
    }

    /**
     * @return The paths of all Smithy sources specified
     *  in this project's smithy build configuration files,
     *  normalized and resolved against {@link #root()}.
     */
    public List<Path> sources() {
        return config.sources().stream()
                .map(root::resolve)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    /**
     * @return The paths of all Smithy imports specified
     *  in this project's smithy build configuration files,
     *  normalized and resolved against {@link #root()}.
     */
    public List<Path> imports() {
        return config.imports().stream()
                .map(root::resolve)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    /**
     * @return The paths of all Smithy files loaded in the project.
     */
    public Set<String> getAllSmithyFilePaths() {
        return this.smithyFiles.keySet();
    }

    /**
     * @return All the Smithy files loaded in the project.
     */
    public Collection<SmithyFile> getAllSmithyFiles() {
        return this.smithyFiles.values();
    }

    public Type type() {
        return type;
    }

    /**
     * @return The latest result of loading this project
     */
    public ValidatedResult<Model> modelResult() {
        return modelResult;
    }

    /**
     * @param uri The uri of the {@link ProjectFile} to get
     * @return The {@link ProjectFile} corresponding to {@code path} if
     *  it exists in this project, otherwise {@code null}.
     */
    public ProjectFile getProjectFile(String uri) {
        String path = LspAdapter.toPath(uri);
        SmithyFile smithyFile = smithyFiles.get(path);
        if (smithyFile != null) {
            return smithyFile;
        }

        return buildFiles.getByPath(path);
    }

    public synchronized void validateConfig() {
        this.configEvents = ProjectConfigLoader.validateBuildFiles(buildFiles);
    }

    /**
     * Update this project's model without running validation.
     *
     * @param uri The URI of the Smithy file to update
     */
    public void updateModelWithoutValidating(String uri) {
        updateFiles(Collections.emptySet(), Collections.emptySet(), Collections.singleton(uri), false);
    }

    /**
     * Update this project's model and run validation.
     *
     * @param uri The URI of the Smithy file to update
     */
    public void updateAndValidateModel(String uri) {
        updateFiles(Collections.emptySet(), Collections.emptySet(), Collections.singleton(uri), true);
    }

    /**
     * Updates this project by adding and removing files. Runs model validation.
     *
     * <p>Added files are assumed to not be managed by the client, and are loaded from disk.
     *
     * @param addUris URIs of files to add
     * @param removeUris URIs of files to remove
     */
    public void updateFiles(Set<String> addUris, Set<String> removeUris) {
        updateFiles(addUris, removeUris, Collections.emptySet(), true);
        // Config has to be re-validated because it may be reporting missing files
        validateConfig();
    }

    /**
     * Updates this project by adding, removing, and changing files. Can optionally run validation.
     *
     * <p>Added files are assumed to not be managed by the client, and are loaded from disk.
     *
     * @param addUris URIs of files to add
     * @param removeUris URIs of files to remove
     * @param changeUris URIs of files that changed
     * @param validate Whether to run model validation.
     */
    private void updateFiles(Set<String> addUris, Set<String> removeUris, Set<String> changeUris, boolean validate) {
        if (modelResult.getResult().isEmpty()) {
            // TODO: If there's no model, we didn't collect the smithy files (so no document), so I'm thinking
            //  maybe we do nothing here. But we could also still update the document, and
            //  just compute the shapes later?
            LOGGER.severe("Attempted to update files in project with no model: "
                          + addUris + " " + removeUris + " " + changeUris);
            return;
        }

        if (addUris.isEmpty() && removeUris.isEmpty() && changeUris.isEmpty()) {
            LOGGER.info("No files provided to update");
            return;
        }

        Model currentModel = modelResult.getResult().get(); // unwrap would throw if the model is broken
        ModelAssembler assembler = assemblerFactory.get();

        // So we don't have to recompute the paths later
        Set<String> removedPaths = new HashSet<>(removeUris.size());

        Set<String> visited = new HashSet<>();

        if (!removeUris.isEmpty() || !changeUris.isEmpty()) {
            Model.Builder builder = prepBuilderForReload(currentModel);

            for (String uri : removeUris) {
                String path = LspAdapter.toPath(uri);
                removedPaths.add(path);

                removeFileForReload(assembler, builder, path, visited);
                removeDependentsForReload(assembler, builder, path, visited);

                // Note: no need to remove anything from sources/imports, since they're
                //  based on what's in the build files.
                smithyFiles.remove(path);
            }

            for (String uri : changeUris) {
                String path = LspAdapter.toPath(uri);

                removeFileForReload(assembler, builder, path, visited);
                removeDependentsForReload(assembler, builder, path, visited);
            }

            // visited will be a superset of removePaths
            addRemainingMetadataForReload(builder, visited);

            assembler.addModel(builder.build());

            for (String visitedPath : visited) {
                // Only add back stuff we aren't trying to remove.
                // Only removed paths will have had their SmithyFile removed.
                if (!removedPaths.contains(visitedPath)) {
                    assembler.addUnparsedModel(visitedPath, smithyFiles.get(visitedPath).document().copyText());
                }
            }
        } else {
            assembler.addModel(currentModel);
        }

        for (String uri : addUris) {
            String path = LspAdapter.toPath(uri);
            String text = IoUtils.readUtf8File(path);

            // TODO: Inefficient ?
            Document document = Document.of(text);
            SmithyFile smithyFile = SmithyFile.create(path, document);
            this.smithyFiles.put(path, smithyFile);

            assembler.addUnparsedModel(path, text);
        }

        if (!validate) {
            assembler.disableValidation();
        }

        this.modelResult = assembler.assemble();
        this.rebuildIndex = this.rebuildIndex.recompute(this.modelResult);
    }

    // This mainly exists to explain why we remove the metadata
    private Model.Builder prepBuilderForReload(Model model) {
        return model.toBuilder()
                // clearing the metadata here, and adding back only metadata from other files
                // is the only sure-fire way to make sure everything is truly removed, and we
                // don't lose anything
                .clearMetadata();
    }

    private void removeFileForReload(
            ModelAssembler assembler,
            Model.Builder builder,
            String path,
            Set<String> visited
    ) {
        if (path == null || visited.contains(path) || path.equals(SourceLocation.none().getFilename())) {
            return;
        }

        visited.add(path);

        for (ToShapeId toShapeId : this.rebuildIndex.getDefinedShapes(path)) {
            builder.removeShape(toShapeId.toShapeId());

            // This shape may have traits applied to it in other files,
            // so simply removing the shape loses the information about
            // those traits.

            // This shape's dependencies files will be removed and re-loaded
            this.rebuildIndex.getDependenciesFiles(toShapeId).forEach((depPath) ->
                    removeFileForReload(assembler, builder, depPath, visited));

            // Traits applied in other files are re-added to the assembler so if/when the shape
            // is reloaded, it will have those traits
            this.rebuildIndex.getTraitsAppliedInOtherFiles(toShapeId).forEach((trait) ->
                    assembler.addTrait(toShapeId.toShapeId(), trait));
        }
    }

    private void removeDependentsForReload(
            ModelAssembler assembler,
            Model.Builder builder,
            String path,
            Set<String> visited
    ) {
        // This file may apply traits to shapes in other files. Normally, letting the assembler simply reparse
        // the file would be fine because it would ignore the duplicated trait application coming from the same
        // source location. But if the apply statement is changed/removed, the old trait isn't removed, so we
        // could get a duplicate application, or a merged array application.
        this.rebuildIndex.getDependentFiles(path).forEach((depPath) ->
                removeFileForReload(assembler, builder, depPath, visited));
        this.rebuildIndex.getAppliedTraitsInFile(path).forEach((shapeId, traits) -> {
            Shape shape = builder.getCurrentShapes().get(shapeId);
            if (shape != null) {
                builder.removeShape(shapeId);
                AbstractShapeBuilder<?, ?> b = Shape.shapeToBuilder(shape);
                for (Trait trait : traits) {
                    b.removeTrait(trait.toShapeId());
                }
                builder.addShape(b.build());
            }
        });
    }

    private void addRemainingMetadataForReload(Model.Builder builder, Set<String> filesToSkip) {
        for (Map.Entry<String, Map<String, Node>> e : this.rebuildIndex.filesToMetadata().entrySet()) {
            if (!filesToSkip.contains(e.getKey())) {
                e.getValue().forEach(builder::putMetadataProperty);
            }
        }
    }

    /**
     * An index that caches rebuild dependency relationships between Smithy files,
     * shapes, traits, and metadata.
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
     *   <dt>A file has metadata</dt>
     *   <dd>Metadata for a specific file has to be removed before reloading that
     *   file, but since array nodes are merged, we also need to keep track of
     *   other files' metadata that may also need to be reloaded.</dd>
     * </dl>
     */
    record RebuildIndex(
            Map<String, Set<String>> filesToDependentFiles,
            Map<ShapeId, Set<String>> shapeIdsToDependenciesFiles,
            Map<String, Map<ShapeId, List<Trait>>> filesToTraitsTheyApply,
            Map<ShapeId, List<Trait>> shapesToAppliedTraitsInOtherFiles,
            Map<String, Map<String, Node>> filesToMetadata,
            Map<String, Set<ToShapeId>> filesToDefinedShapes
    ) {
        private RebuildIndex() {
            this(
                    new HashMap<>(0),
                    new HashMap<>(0),
                    new HashMap<>(0),
                    new HashMap<>(0),
                    new HashMap<>(0),
                    new HashMap<>(0)
            );
        }

        static RebuildIndex create(ValidatedResult<Model> modelResult) {
            return new RebuildIndex().recompute(modelResult);
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

        Set<ToShapeId> getDefinedShapes(String path) {
            return filesToDefinedShapes.getOrDefault(path, Collections.emptySet());
        }

        RebuildIndex recompute(ValidatedResult<Model> modelResult) {
            var newIndex = new RebuildIndex(
                    new HashMap<>(filesToDependentFiles.size()),
                    new HashMap<>(shapeIdsToDependenciesFiles.size()),
                    new HashMap<>(filesToTraitsTheyApply.size()),
                    new HashMap<>(shapesToAppliedTraitsInOtherFiles.size()),
                    new HashMap<>(filesToMetadata.size()),
                    new HashMap<>(filesToDefinedShapes.size())
            );

            if (modelResult.getResult().isEmpty()) {
                return newIndex;
            }

            Model model =  modelResult.getResult().get();

            // This is gross, but necessary to deal with the way that array metadata gets merged.
            // When we try to reload a single file, we need to make sure we remove the metadata for
            // that file. But if there's array metadata, a single key contains merged elements from
            // other files. This splits up the metadata by source file, creating an artificial array
            // node for elements that are merged.
            for (var metadataEntry : model.getMetadata().entrySet()) {
                if (metadataEntry.getValue().isArrayNode()) {
                    Map<String, ArrayNode.Builder> arrayByFile = new HashMap<>();
                    for (Node node : metadataEntry.getValue().expectArrayNode()) {
                        String filename = node.getSourceLocation().getFilename();
                        arrayByFile.computeIfAbsent(filename, (f) -> ArrayNode.builder()).withValue(node);
                    }
                    for (var arrayByFileEntry : arrayByFile.entrySet()) {
                        newIndex.filesToMetadata.computeIfAbsent(arrayByFileEntry.getKey(), (f) -> new HashMap<>())
                                .put(metadataEntry.getKey(), arrayByFileEntry.getValue().build());
                    }
                } else {
                    String filename = metadataEntry.getValue().getSourceLocation().getFilename();
                    newIndex.filesToMetadata.computeIfAbsent(filename, (f) -> new HashMap<>())
                            .put(metadataEntry.getKey(), metadataEntry.getValue());
                }
            }

            for (Shape shape : model.toSet()) {
                String shapeSourceFilename = shape.getSourceLocation().getFilename();
                newIndex.filesToDefinedShapes.computeIfAbsent(shapeSourceFilename, (f) -> new HashSet<>())
                        .add(shape);

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
                                newIndex.filesToDependentFiles
                                        .computeIfAbsent(elementSourceFilename, (f) -> new HashSet<>())
                                        .add(shapeSourceFilename);
                                newIndex.shapeIdsToDependenciesFiles
                                        .computeIfAbsent(shape.getId(), (i) -> new HashSet<>())
                                        .add(elementSourceFilename);
                            }
                        }
                    } else {
                        String traitSourceFilename = traitApplication.getSourceLocation().getFilename();
                        if (!traitSourceFilename.equals(shapeSourceFilename)) {
                            newIndex.shapesToAppliedTraitsInOtherFiles
                                    .computeIfAbsent(shape.getId(), (i) -> new ArrayList<>())
                                    .add(traitApplication);
                            newIndex.filesToTraitsTheyApply
                                    .computeIfAbsent(traitSourceFilename, (f) -> new HashMap<>())
                                    .computeIfAbsent(shape.getId(), (i) -> new ArrayList<>())
                                    .add(traitApplication);
                        }
                    }
                }
            }

            return newIndex;
        }
    }
}
