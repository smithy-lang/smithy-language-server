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
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.UriAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
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
    // TODO: Probably should move this into SmithyFile
    private Map<String, Map<String, Node>> perFileMetadata;

    private Project(Builder builder) {
        this.root = Objects.requireNonNull(builder.root);
        this.config = Objects.requireNonNull(builder.config);
        this.dependencies = builder.dependencies;
        this.smithyFiles = builder.smithyFiles;
        this.modelResult = builder.modelResult;
        this.assemblerFactory = builder.assemblerFactory;
        this.perFileMetadata = builder.perFileMetadata;
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
        Document document = getDocument(uri);
        updateModel(uri, document, false);
    }

    /**
     * Update this project's model and run validation.
     *
     * @param uri The URI of the Smithy file to update
     */
    public void updateAndValidateModel(String uri) {
        Document document = getDocument(uri);
        updateModel(uri, document, true);
    }

    // TODO: This is a little all over the place
    /**
     * Update the model with the contents of the given {@code document}, optionally
     * running validation.
     *
     * @param uri The URI of the Smithy file to update
     * @param document The {@link Document} with updated contents
     * @param validate Whether to run validation
     */
    public void updateModel(String uri, Document document, boolean validate) {
        if (document == null || !modelResult.getResult().isPresent()) {
            // TODO: At one point in testing, the server got stuck with a certain validation event
            //  always being present, and no other features working. I haven't been able to reproduce
            //  it, but I added these logs to check for it.
            if (document == null) {
                LOGGER.info("No document loaded for " + uri + ", skipping model load.");
            }
            if (!modelResult.getResult().isPresent()) {
                LOGGER.info("No model loaded, skipping updating model with " + uri);
            }
            // TODO: If there's no model, we didn't collect the smithy files (so no document), so I'm thinking
            //  maybe we do nothing here. But we could also still update the document, and
            //  just compute the shapes later?
            return;
        }

        String path = UriAdapter.toPath(uri);

        SmithyFile previous = smithyFiles.get(path);
        Model currentModel = modelResult.getResult().get(); // unwrap would throw if the model is broken

        Model.Builder builder = prepBuilderForReload(currentModel);
        for (Shape shape : previous.getShapes()) {
            builder.removeShape(shape.getId());
        }
        for (Map.Entry<String, Map<String, Node>> e : this.perFileMetadata.entrySet()) {
            if (!e.getKey().equals(path)) {
                e.getValue().forEach(builder::putMetadataProperty);
            }
        }
        Model rest = builder.build();

        ModelAssembler assembler = assemblerFactory.get()
                .addModel(rest)
                .addUnparsedModel(path, document.copyText());

        if (!validate) {
            assembler.disableValidation();
        }

        this.modelResult = assembler.assemble();

        Set<Shape> updatedShapes = getFileShapes(path, previous.getShapes());
        // TODO: Could cache validation events
        SmithyFile updated = ProjectLoader.buildSmithyFile(path, document, updatedShapes).build();
        this.perFileMetadata = ProjectLoader.computePerFileMetadata(this.modelResult);
        this.smithyFiles.put(path, updated);
    }

    /**
     * Updates this project by adding and removing files. Also runs model validation.
     *
     * @param addUris URIs of files to add
     * @param removeUris URIs of files to remove
     */
    public void updateFiles(Collection<String> addUris, Collection<String> removeUris) {
        if (!modelResult.getResult().isPresent()) {
            LOGGER.severe("Attempted to update files in project with no model: " + addUris + " " + removeUris);
            return;
        }

        if (addUris.isEmpty() && removeUris.isEmpty()) {
            LOGGER.info("No files provided to update");
            return;
        }

        Model currentModel = modelResult.getResult().get();
        ModelAssembler assembler = assemblerFactory.get();
        if (!removeUris.isEmpty()) {
            // Slightly strange way to do this, but we need to remove all model metadata, then
            // re-add only metadata for remaining files.
            Set<String> remainingFilesWithMetadata = new HashSet<>(perFileMetadata.keySet());

            Model.Builder builder = prepBuilderForReload(currentModel);

            for (String uri : removeUris) {
                String path = UriAdapter.toPath(uri);

                remainingFilesWithMetadata.remove(path);

                // Note: no need to remove anything from sources/imports, since they're
                //  based on what's in the build files.
                SmithyFile smithyFile = smithyFiles.remove(path);

                if (smithyFile == null) {
                    LOGGER.severe("Attempted to remove file not in project: " + uri);
                    continue;
                }
                for (Shape shape : smithyFile.getShapes()) {
                    builder.removeShape(shape.getId());
                }
            }
            for (String remainingFileWithMetadata : remainingFilesWithMetadata) {
                Map<String, Node> fileMetadata = perFileMetadata.get(remainingFileWithMetadata);
                for (Map.Entry<String, Node> fileMetadataEntry : fileMetadata.entrySet()) {
                    builder.putMetadataProperty(fileMetadataEntry.getKey(), fileMetadataEntry.getValue());
                }
            }
            assembler.addModel(builder.build());
        } else {
            assembler.addModel(currentModel);
        }

        for (String uri : addUris) {
            assembler.addImport(UriAdapter.toPath(uri));
        }

        this.modelResult = assembler.assemble();
        this.perFileMetadata = ProjectLoader.computePerFileMetadata(this.modelResult);

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
        private ProjectConfig config;
        private final List<Path> dependencies = new ArrayList<>();
        private final Map<String, SmithyFile> smithyFiles = new HashMap<>();
        private ValidatedResult<Model> modelResult;
        private Supplier<ModelAssembler> assemblerFactory = Model::assembler;
        private Map<String, Map<String, Node>> perFileMetadata = new HashMap<>();

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

        public Project build() {
            return new Project(this);
        }
    }
}
