/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import software.amazon.smithy.lsp.ManagedFiles;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.TriConsumer;

/**
 * Loads {@link Project}s.
 */
public final class ProjectLoader {
    private ProjectLoader() {
    }

    /**
     * Loads a detachedProjects (single-file) {@link Project} with the given file.
     *
     * <p>Unlike {@link #load(Path, ManagedFiles)}, this method isn't
     * fallible since it doesn't do any IO that we would want to recover an
     * error from.
     *
     * @param uri URI of the file to load into a project
     * @param text Text of the file to load into a project
     * @return The loaded project
     */
    public static Project loadDetached(String uri, String text) {
        Document document = Document.of(text);
        ManagedFiles managedFiles = (fileUri) -> {
            if (uri.equals(fileUri)) {
                return document;
            }
            return null;
        };

        Path path = Paths.get(LspAdapter.toPath(uri));
        ProjectConfig config = ProjectConfig.detachedConfig(path);
        LoadModelResult result = doLoad(managedFiles, config);

        return new Project(
                path,
                config,
                BuildFiles.of(List.of()),
                result.smithyFiles(),
                result.assemblerFactory(),
                Project.Type.DETACHED,
                result.modelResult(),
                result.rebuildIndex(),
                List.of()
        );
    }

    /**
     * Loads a {@link Project} at the given root path, using any {@code managedDocuments}
     * instead of loading from disk.
     *
     * <p>This will return a failed result if loading the project config, resolving
     * the dependencies, or creating the model assembler fail.
     *
     * <p>The build configuration files are the single source of truth for what will
     * be loaded. Previous behavior in the language server was to walk all subdirs of
     * the root and find all the .smithy files, but this made it challenging to
     * reason about how the project was structured.
     *
     * @param root Path of the project root
     * @param managedFiles Files managed by the server
     * @return Result of loading the project
     */
    public static Project load(Path root, ManagedFiles managedFiles) throws Exception {
        var buildFiles = BuildFiles.load(root, managedFiles);
        if (buildFiles.isEmpty()) {
            return Project.empty(root);
        }

        ProjectConfigLoader.Result configResult = ProjectConfigLoader.load(root, buildFiles);
        LoadModelResult result = doLoad(managedFiles, configResult.config());

        return new Project(
                root,
                configResult.config(),
                buildFiles,
                result.smithyFiles(),
                result.assemblerFactory(),
                Project.Type.NORMAL,
                result.modelResult(),
                result.rebuildIndex(),
                configResult.events()
        );
    }

    private record LoadModelResult(
            Supplier<ModelAssembler> assemblerFactory,
            ValidatedResult<Model> modelResult,
            Map<String, SmithyFile> smithyFiles,
            Project.RebuildIndex rebuildIndex
    ) {
    }

    private static LoadModelResult doLoad(ManagedFiles managedFiles, ProjectConfig config) {
        // The model assembler factory is used to get assemblers that already have the correct
        // dependencies resolved for future loads
        Supplier<ModelAssembler> assemblerFactory = createModelAssemblerFactory(config.resolvedDependencies());

        Map<String, SmithyFile> smithyFiles = new HashMap<>(config.modelPaths().size());

        ModelAssembler assembler = assemblerFactory.get();
        ValidatedResult<Model> modelResult = loadModel(managedFiles, config.modelPaths(), assembler, smithyFiles);

        Project.RebuildIndex rebuildIndex = Project.RebuildIndex.create(modelResult);
        addDependencySmithyFiles(managedFiles, rebuildIndex.filesToDefinedShapes().keySet(), smithyFiles);

        return new LoadModelResult(
                assemblerFactory,
                modelResult,
                smithyFiles,
                rebuildIndex
        );
    }

    private static ValidatedResult<Model> loadModel(
            ManagedFiles managedFiles,
            List<Path> allSmithyFilePaths,
            ModelAssembler assembler,
            Map<String, SmithyFile> smithyFiles
    ) {
        TriConsumer<String, CharSequence, Document> consumer = (filePath, text, document) -> {
            assembler.addUnparsedModel(filePath, text.toString());
            smithyFiles.put(filePath, SmithyFile.create(filePath, document));
        };

        for (Path path : allSmithyFilePaths) {
            String pathString = path.toString();
            findOrReadDocument(managedFiles, pathString, consumer);
        }

        return assembler.assemble();
    }

    // Smithy files in jars were loaded by the model assembler via model discovery, so we need to collect those.
    private static void addDependencySmithyFiles(
            ManagedFiles managedFiles,
            Set<String> loadedSmithyFilePaths,
            Map<String, SmithyFile> smithyFiles
    ) {
        TriConsumer<String, CharSequence, Document> consumer = (filePath, text, document) -> {
            SmithyFile smithyFile = SmithyFile.create(filePath, document);
            smithyFiles.put(filePath, smithyFile);
        };

        for (String loadedPath : loadedSmithyFilePaths) {
            if (!smithyFiles.containsKey(loadedPath)) {
                findOrReadDocument(managedFiles, loadedPath, consumer);
            }
        }
    }

    private static void findOrReadDocument(
            ManagedFiles managedFiles,
            String filePath,
            TriConsumer<String, CharSequence, Document> consumer
    ) {
        // NOTE: isSmithyJarFile and isJarFile typically take in a URI (filePath is a path), but
        // the model stores jar paths as URIs
        if (LspAdapter.isSmithyJarFile(filePath) || LspAdapter.isJarFile(filePath)) {
            // Technically this can throw
            String text = IoUtils.readUtf8Url(LspAdapter.jarUrl(filePath));
            Document document = Document.of(text);
            consumer.accept(filePath, text, document);
            return;
        }

        // TODO: We recompute uri from path and vice-versa very frequently,
        //  maybe we can cache it.
        String uri = LspAdapter.toUri(filePath);
        Document managed = managedFiles.getManagedDocument(uri);
        if (managed != null) {
            CharSequence text = managed.borrowText();
            consumer.accept(filePath, text, managed);
            return;
        }

        // There may be a more efficient way of reading this
        String text = IoUtils.readUtf8File(filePath);
        Document document = Document.of(text);
        consumer.accept(filePath, text, document);
    }

    private static Supplier<ModelAssembler> createModelAssemblerFactory(List<URL> dependencies) {
        // We don't want the model to be broken when there are unknown traits,
        // because that will essentially disable language server features, so
        // we need to allow unknown traits for each factory.

        if (dependencies.isEmpty()) {
            return () -> Model.assembler().putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
        }

        URL[] urls = dependencies.toArray(new URL[0]);
        URLClassLoader classLoader = new URLClassLoader(urls);
        return () -> Model.assembler(classLoader)
                .discoverModels(classLoader)
                .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
    }
}
