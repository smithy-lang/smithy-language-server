/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.ValidatedResult;

/**
 * A Smithy project open on the client. It keeps track of its Smithy files and
 * dependencies, and the currently loaded model.
 */
public final class Project {
    private static final Logger LOGGER = Logger.getLogger(Project.class.getName());
    private final Path root;
    private final List<Path> sources;
    private final List<Path> imports;
    private final List<Path> dependencies;
    private final Map<String, SmithyFile> smithyFiles;
    private final Supplier<ModelAssembler> assemblerFactory;
    private ValidatedResult<Model> modelResult;

    private Project(Builder builder) {
        this.root = Objects.requireNonNull(builder.root);
        this.sources = builder.sources;
        this.imports = builder.imports;
        this.dependencies = builder.dependencies;
        this.smithyFiles = builder.smithyFiles;
        this.modelResult = builder.modelResult;
        this.assemblerFactory = builder.assemblerFactory;
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
     * @return The paths of all Smithy sources, exactly as they were specified
     *  in this project's smithy build configuration files
     */
    public List<Path> getSources() {
        return sources;
    }

    /**
     * @return The paths of all imports, exactly as they were specified in this
     *  project's smithy build configuration files
     */
    public List<Path> getImports() {
        return imports;
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

        Model.Builder builder = currentModel.toBuilder();
        for (Shape shape : previous.getShapes()) {
            builder.removeShape(shape.getId());
        }
        Model rest = builder.build();

        ModelAssembler assembler = assemblerFactory.get()
                .addModel(rest)
                .addUnparsedModel(path, document.copyText());

        if (!validate) {
            assembler.disableValidation();
        }

        this.modelResult = assembler.assemble();

        Set<Shape> updatedShapes = modelResult.getResult()
                .map(model -> model.shapes()
                        .filter(shape -> shape.getSourceLocation().getFilename().equals(path))
                        .collect(Collectors.toSet()))
                .orElse(previous.getShapes());

        // TODO: Could cache validation events
        SmithyFile updated = ProjectLoader.buildSmithyFile(path, document, updatedShapes).build();
        this.smithyFiles.put(path, updated);
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private Path root;
        private final List<Path> sources = new ArrayList<>();
        private final List<Path> imports = new ArrayList<>();
        private final List<Path> dependencies = new ArrayList<>();
        private final Map<String, SmithyFile> smithyFiles = new HashMap<>();
        private ValidatedResult<Model> modelResult;
        private Supplier<ModelAssembler> assemblerFactory = Model::assembler;

        private Builder() {
        }

        public Builder root(Path root) {
            this.root = root;
            return this;
        }

        public Builder sources(List<Path> paths) {
            this.sources.clear();
            this.sources.addAll(paths);
            return this;
        }

        public Builder addSource(Path path) {
            this.sources.add(path);
            return this;
        }

        public Builder imports(List<Path> paths) {
            this.imports.clear();
            this.imports.addAll(paths);
            return this;
        }

        public Builder addImport(Path path) {
            this.imports.add(path);
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

        public Project build() {
            return new Project(this);
        }
    }
}
