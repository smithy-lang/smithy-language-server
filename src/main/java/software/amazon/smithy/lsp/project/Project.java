/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.UriAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.AbstractShapeBuilder;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;

/**
 * A Smithy project open on the client. It keeps track of its Smithy files and
 * dependencies, and the currently loaded model.
 */
public final class Project {
    private static final Logger LOGGER = Logger.getLogger(Project.class.getName());
    private final Path root;
    private final ProjectConfig config;
    private final List<Path> dependencies;
    private final Map<String, SmithyFile> smithyFiles;
    private final Supplier<ModelAssembler> assemblerFactory;
    private ValidatedResult<Model> modelResult;
    // TODO: Move this into SmithyFileDependenciesIndex
    private Map<String, Map<String, Node>> perFileMetadata;
    private SmithyFileDependenciesIndex smithyFileDependenciesIndex;

    private Project(Builder builder) {
        this.root = Objects.requireNonNull(builder.root);
        this.config = builder.config;
        this.dependencies = builder.dependencies;
        this.smithyFiles = builder.smithyFiles;
        this.modelResult = builder.modelResult;
        this.assemblerFactory = builder.assemblerFactory;
        this.perFileMetadata = builder.perFileMetadata;
        this.smithyFileDependenciesIndex = builder.smithyFileDependenciesIndex;
    }

    /**
     * Create an empty project with no Smithy files, dependencies, or loaded model.
     *
     * @param root Root path of the project
     * @return The empty project
     */
    public static Project empty(Path root) {
        return builder()
                .root(root)
                .modelResult(ValidatedResult.empty())
                .build();
    }

    /**
     * @return The path of the root directory of the project
     */
    public Path getRoot() {
        return root;
    }

    /**
     * @return The paths of all Smithy sources specified
     *  in this project's smithy build configuration files,
     *  normalized and resolved against {@link #getRoot()}.
     */
    public List<Path> getSources() {
        return config.getSources().stream()
                .map(root::resolve)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    /**
     * @return The paths of all Smithy imports specified
     *  in this project's smithy build configuration files,
     *  normalized and resolved against {@link #getRoot()}.
     */
    public List<Path> getImports() {
        return config.getImports().stream()
                .map(root::resolve)
                .map(Path::normalize)
                .collect(Collectors.toList());
    }

    /**
     * @return The paths of all resolved dependencies
     */
    public List<Path> getDependencies() {
        return dependencies;
    }

    /**
     * @return A map of paths to the {@link SmithyFile} at that path, containing
     *  all smithy files loaded in the project.
     */
    public Map<String, SmithyFile> getSmithyFiles() {
        return this.smithyFiles;
    }

    /**
     * @return The latest result of loading this project
     */
    public ValidatedResult<Model> getModelResult() {
        return modelResult;
    }

    /**
     * @param uri The URI of the {@link Document} to get
     * @return The {@link Document} corresponding to the given {@code uri} if
     *  it exists in this project, otherwise {@code null}
     */
    public Document getDocument(String uri) {
        String path = UriAdapter.toPath(uri);
        SmithyFile smithyFile = smithyFiles.get(path);
        if (smithyFile == null) {
            return null;
        }
        return smithyFile.getDocument();
    }

    /**
     * @param uri The URI of the {@link SmithyFile} to get
     * @return The {@link SmithyFile} corresponding to the given {@code uri} if
     *  it exists in this project, otherwise {@code null}
     */
    public SmithyFile getSmithyFile(String uri) {
        String path = UriAdapter.toPath(uri);
        return smithyFiles.get(path);
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
    public void updateFiles(Set<String> addUris, Set<String> removeUris, Set<String> changeUris, boolean validate) {
        if (!modelResult.getResult().isPresent()) {
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
        Set<String> changedPaths = new HashSet<>(changeUris.size());

        Set<String> visited = new HashSet<>();

        if (!removeUris.isEmpty() || !changeUris.isEmpty()) {
            Model.Builder builder = prepBuilderForReload(currentModel);

            for (String uri : removeUris) {
                String path = UriAdapter.toPath(uri);
                removedPaths.add(path);

                removeFileForReload(assembler, builder, path, visited);
                removeDependentsForReload(assembler, builder, path, visited);

                // Note: no need to remove anything from sources/imports, since they're
                //  based on what's in the build files.
                smithyFiles.remove(path);
            }

            for (String uri : changeUris) {
                String path = UriAdapter.toPath(uri);
                changedPaths.add(path);

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
                    assembler.addUnparsedModel(visitedPath, smithyFiles.get(visitedPath).getDocument().copyText());
                }
            }
        } else {
            assembler.addModel(currentModel);
        }

        for (String uri : addUris) {
            assembler.addImport(UriAdapter.toPath(uri));
        }

        if (!validate) {
            assembler.disableValidation();
        }

        this.modelResult = assembler.assemble();
        this.perFileMetadata = ProjectLoader.computePerFileMetadata(this.modelResult);
        this.smithyFileDependenciesIndex = SmithyFileDependenciesIndex.compute(this.modelResult);

        for (String visitedPath : visited) {
            if (!removedPaths.contains(visitedPath)) {
                SmithyFile current = smithyFiles.get(visitedPath);
                Set<Shape> updatedShapes = getFileShapes(visitedPath, smithyFiles.get(visitedPath).getShapes());
                // Only recompute the rest of the smithy file if it changed
                if (changedPaths.contains(visitedPath)) {
                    // TODO: Could cache validation events
                    this.smithyFiles.put(visitedPath,
                            ProjectLoader.buildSmithyFile(visitedPath, current.getDocument(), updatedShapes).build());
                } else {
                    current.setShapes(updatedShapes);
                }
            }
        }

        for (String uri : addUris) {
            String path = UriAdapter.toPath(uri);
            Set<Shape> fileShapes = getFileShapes(path, Collections.emptySet());
            Document document = Document.of(IoUtils.readUtf8File(path));
            SmithyFile smithyFile = ProjectLoader.buildSmithyFile(path, document, fileShapes)
                    .build();
            smithyFiles.put(path, smithyFile);
        }
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

        for (Shape shape : smithyFiles.get(path).getShapes()) {
            builder.removeShape(shape.getId());

            // This shape may have traits applied to it in other files,
            // so simply removing the shape loses the information about
            // those traits.

            // This shape's dependencies files will be removed and re-loaded
            smithyFileDependenciesIndex.getDependenciesFiles(shape).forEach((depPath) ->
                    removeFileForReload(assembler, builder, depPath, visited));

            // Traits applied in other files are re-added to the assembler so if/when the shape
            // is reloaded, it will have those traits
            smithyFileDependenciesIndex.getTraitsAppliedInOtherFiles(shape).forEach((trait) ->
                    assembler.addTrait(shape.getId(), trait));
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
        // source location. But if the apply statement is changed/removed, the old application isn't removed, so we
        // could get a duplicate trait, or a merged array trait.
        smithyFileDependenciesIndex.getDependentFiles(path).forEach((depPath) -> {
            removeFileForReload(assembler, builder, depPath, visited);
        });
        smithyFileDependenciesIndex.getAppliedTraitsInFile(path).forEach((shapeId, traits) -> {
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
        for (Map.Entry<String, Map<String, Node>> e : this.perFileMetadata.entrySet()) {
            if (!filesToSkip.contains(e.getKey())) {
                e.getValue().forEach(builder::putMetadataProperty);
            }
        }
    }

    private Set<Shape> getFileShapes(String path, Set<Shape> orDefault) {
        return this.modelResult.getResult()
                .map(model -> model.shapes()
                        .filter(shape -> shape.getSourceLocation().getFilename().equals(path))
                        .collect(Collectors.toSet()))
                .orElse(orDefault);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Path root;
        private ProjectConfig config = ProjectConfig.empty();
        private final List<Path> dependencies = new ArrayList<>();
        private final Map<String, SmithyFile> smithyFiles = new HashMap<>();
        private ValidatedResult<Model> modelResult;
        private Supplier<ModelAssembler> assemblerFactory = Model::assembler;
        private Map<String, Map<String, Node>> perFileMetadata = new HashMap<>();
        private SmithyFileDependenciesIndex smithyFileDependenciesIndex = SmithyFileDependenciesIndex.EMPTY;

        private Builder() {
        }

        public Builder root(Path root) {
            this.root = root;
            return this;
        }

        public Builder config(ProjectConfig config) {
            this.config = config;
            return this;
        }

        public Builder dependencies(List<Path> paths) {
            this.dependencies.clear();
            this.dependencies.addAll(paths);
            return this;
        }

        public Builder addDependency(Path path) {
            this.dependencies.add(path);
            return this;
        }

        public Builder smithyFiles(Map<String, SmithyFile> smithyFiles) {
            this.smithyFiles.clear();
            this.smithyFiles.putAll(smithyFiles);
            return this;
        }

        public Builder modelResult(ValidatedResult<Model> modelResult) {
            this.modelResult = modelResult;
            return this;
        }

        public Builder assemblerFactory(Supplier<ModelAssembler> assemblerFactory) {
            this.assemblerFactory = assemblerFactory;
            return this;
        }

        public Builder perFileMetadata(Map<String, Map<String, Node>> perFileMetadata) {
            this.perFileMetadata = perFileMetadata;
            return this;
        }

        public Builder smithyFileDependenciesIndex(SmithyFileDependenciesIndex smithyFileDependenciesIndex) {
            this.smithyFileDependenciesIndex = smithyFileDependenciesIndex;
            return this;
        }

        public Project build() {
            return new Project(this);
        }
    }
}
