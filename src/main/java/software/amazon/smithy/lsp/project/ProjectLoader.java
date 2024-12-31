/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Stream;
import software.amazon.smithy.lsp.ManagedFiles;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.loader.ModelDiscovery;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.TriConsumer;

/**
 * Loads {@link Project}s.
 *
 * TODO: There's a lot of duplicated logic and redundant code here to refactor.
 */
public final class ProjectLoader {
    private static final Logger LOGGER = Logger.getLogger(ProjectLoader.class.getName());

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
        LOGGER.info("Loading detachedProjects project at " + uri);

        String asPath = LspAdapter.toPath(uri);
        Path path = Paths.get(asPath);
        List<Path> allSmithyFilePaths = List.of(path);

        Document document = Document.of(text);
        ManagedFiles managedFiles = (fileUri) -> {
            if (uri.equals(fileUri)) {
                return document;
            }
            return null;
        };

        List<Path> dependencies = List.of();

        LoadModelResult result;
        try {
            result = doLoad(managedFiles, dependencies, allSmithyFilePaths);
        } catch (Exception e) {
            // TODO: Clean up this comment
            // Note: This can't happen because we have no dependencies to turn into URLs
            throw new RuntimeException(e);
        }

        return new Project(
                path,
                ProjectConfig.empty(),
                dependencies,
                result.smithyFiles(),
                result.assemblerFactory(),
                Project.Type.DETACHED,
                result.modelResult(),
                result.rebuildIndex()
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
    public static Result<Project, List<Exception>> load(Path root, ManagedFiles managedFiles) {
        Result<ProjectConfig, List<Exception>> configResult = ProjectConfigLoader.loadFromRoot(root, managedFiles);
        if (configResult.isErr()) {
            return Result.err(configResult.unwrapErr());
        }
        ProjectConfig config = configResult.unwrap();

        Result<List<Path>, Exception> resolveResult = ProjectDependencyResolver.resolveDependencies(root, config);
        if (resolveResult.isErr()) {
            return Result.err(Collections.singletonList(resolveResult.unwrapErr()));
        }

        List<Path> dependencies = resolveResult.unwrap();

        // Note: The model assembler can handle loading all smithy files in a directory, so there's some potential
        //  here for inconsistent behavior.
        List<Path> allSmithyFilePaths = collectAllSmithyPaths(root, config.sources(), config.imports());

        LoadModelResult result;
        try {
            result = doLoad(managedFiles, dependencies, allSmithyFilePaths);
        } catch (Exception e) {
            return Result.err(Collections.singletonList(e));
        }

        return Result.ok(new Project(
                root,
                config,
                dependencies,
                result.smithyFiles(),
                result.assemblerFactory(),
                Project.Type.NORMAL,
                result.modelResult(),
                result.rebuildIndex()
        ));
    }

    private record LoadModelResult(
            Supplier<ModelAssembler> assemblerFactory,
            ValidatedResult<Model> modelResult,
            Map<String, SmithyFile> smithyFiles,
            Project.RebuildIndex rebuildIndex
    ) {
    }

    private static LoadModelResult doLoad(
            ManagedFiles managedFiles,
            List<Path> dependencies,
            List<Path> allSmithyFilePaths
    ) throws Exception {
        // The model assembler factory is used to get assemblers that already have the correct
        // dependencies resolved for future loads
        Supplier<ModelAssembler> assemblerFactory = createModelAssemblerFactory(dependencies);

        Map<String, SmithyFile> smithyFiles = new HashMap<>(allSmithyFilePaths.size());

        // TODO: Assembler can fail if a file is not found. We can be more intelligent about
        //  handling this case to allow partially loading the project, but we will need to
        //  collect and report the errors somehow. For now, using collectAllSmithyPaths skips
        //  any files that don't exist, so we're essentially side-stepping the issue by
        //  coincidence.
        ModelAssembler assembler = assemblerFactory.get();
        ValidatedResult<Model> modelResult = loadModel(managedFiles, allSmithyFilePaths, assembler, smithyFiles);

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

    private static Supplier<ModelAssembler> createModelAssemblerFactory(List<Path> dependencies)
            throws MalformedURLException {
        // We don't want the model to be broken when there are unknown traits,
        // because that will essentially disable language server features, so
        // we need to allow unknown traits for each factory.

        if (dependencies.isEmpty()) {
            return () -> Model.assembler().putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
        }

        URLClassLoader classLoader = createDependenciesClassLoader(dependencies);
        return () -> Model.assembler(classLoader)
                .discoverModels(classLoader)
                .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
    }

    private static URLClassLoader createDependenciesClassLoader(List<Path> dependencies) throws MalformedURLException {
        // Taken (roughly) from smithy-ci IsolatedRunnable
        URL[] urls = new URL[dependencies.size()];
        int i = 0;
        for (Path dependency : dependencies) {
            urls[i++] = dependency.toUri().toURL();
        }
        return new URLClassLoader(urls);
    }

    // TODO: Can there be duplicate paths in this list? If there are, we may end up reading from disk multiple times
    // sources and imports can contain directories or files, relative or absolute
    private static List<Path> collectAllSmithyPaths(Path root, List<String> sources, List<String> imports) {
        List<Path> paths = new ArrayList<>();
        for (String file : sources) {
            Path path = root.resolve(file).normalize();
            collectDirectory(paths, root, path);
        }
        for (String file : imports) {
            Path path = root.resolve(file).normalize();
            collectDirectory(paths, root, path);
        }
        return paths;
    }

    // All of this copied from smithy-build SourcesPlugin
    private static void collectDirectory(List<Path> accumulator, Path root, Path current) {
        try {
            if (Files.isDirectory(current)) {
                try (Stream<Path> paths = Files.list(current)) {
                    paths.filter(p -> !p.equals(current))
                            .filter(p -> Files.isDirectory(p) || Files.isRegularFile(p))
                            .forEach(p -> collectDirectory(accumulator, root, p));
                }
            } else if (Files.isRegularFile(current)) {
                if (current.toString().endsWith(".jar")) {
                    String jarRoot = root.equals(current)
                            ? current.toString()
                            : (current + File.separator);
                    collectJar(accumulator, jarRoot, current);
                } else {
                    collectFile(accumulator, current);
                }
            }
        } catch (IOException ignored) {
            // For now just ignore this - the assembler would have run into the same issues
        }
    }

    private static void collectJar(List<Path> accumulator, String jarRoot, Path jarPath) {
        URL manifestUrl = ModelDiscovery.createSmithyJarManifestUrl(jarPath.toString());

        String prefix = computeJarFilePrefix(jarRoot, jarPath);
        for (URL model : ModelDiscovery.findModels(manifestUrl)) {
            String name = ModelDiscovery.getSmithyModelPathFromJarUrl(model);
            Path target = Paths.get(prefix + name);
            collectFile(accumulator, target);
        }
    }

    private static String computeJarFilePrefix(String jarRoot, Path jarPath) {
        Path jarFilenamePath = jarPath.getFileName();

        if (jarFilenamePath == null) {
            return jarRoot;
        }

        String jarFilename = jarFilenamePath.toString();
        return jarRoot + jarFilename.substring(0, jarFilename.length() - ".jar".length()) + File.separator;
    }

    private static void collectFile(List<Path> accumulator, Path target) {
        if (target == null) {
            return;
        }
        String filename = target.toString();
        if (filename.endsWith(".smithy") || filename.endsWith(".json")) {
            accumulator.add(target);
        }
    }
}
