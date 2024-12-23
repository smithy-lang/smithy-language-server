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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.loader.ModelDiscovery;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;

/**
 * Loads {@link Project}s.
 *
 * TODO: There's a lot of duplicated logic and redundant code here to refactor.
 *
 * TODO: Inefficient creation of smithy files
 */
public final class ProjectLoader {
    private static final Logger LOGGER = Logger.getLogger(ProjectLoader.class.getName());

    private ProjectLoader() {
    }

    /**
     * Loads a detachedProjects (single-file) {@link Project} with the given file.
     *
     * <p>Unlike {@link #load(Path, ServerState)}, this method isn't
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
        Supplier<ModelAssembler> assemblerFactory;
        try {
            assemblerFactory = createModelAssemblerFactory(List.of());
        } catch (MalformedURLException e) {
            // Note: This can't happen because we have no dependencies to turn into URLs
            throw new RuntimeException(e);
        }

        ValidatedResult<Model> modelResult = assemblerFactory.get()
                .addUnparsedModel(asPath, text)
                .assemble();

        Path path = Paths.get(asPath);
        List<Path> sources = Collections.singletonList(path);

        var definedShapesByFile = computeDefinedShapesByFile(sources, modelResult);
        var smithyFiles = createSmithyFiles(definedShapesByFile, (filePath) -> {
            // NOTE: isSmithyJarFile and isJarFile typically take in a URI (filePath is a path), but
            // the model stores jar paths as URIs
            if (LspAdapter.isSmithyJarFile(filePath) || LspAdapter.isJarFile(filePath)) {
                return Document.of(IoUtils.readUtf8Url(LspAdapter.jarUrl(filePath)));
            } else if (filePath.equals(asPath)) {
                return Document.of(text);
            } else {
                // TODO: Make generic 'please file a bug report' exception
                throw new IllegalStateException(
                        "Attempted to load an unknown source file ("
                        + filePath + ") in detachedProjects project at "
                        + asPath + ". This is a bug in the language server.");
            }
        });

        return new Project(path,
                ProjectConfig.builder().sources(List.of(asPath)).build(),
                List.of(),
                smithyFiles,
                assemblerFactory,
                Project.Type.DETACHED,
                modelResult,
                Project.RebuildIndex.create(modelResult));
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
     * @param state Server's current state
     * @return Result of loading the project
     */
    public static Result<Project, List<Exception>> load(Path root, ServerState state) {
        Result<ProjectConfig, List<Exception>> configResult = ProjectConfigLoader.loadFromRoot(root, state);
        if (configResult.isErr()) {
            return Result.err(configResult.unwrapErr());
        }
        ProjectConfig config = configResult.unwrap();

        Result<List<Path>, Exception> resolveResult = ProjectDependencyResolver.resolveDependencies(root, config);
        if (resolveResult.isErr()) {
            return Result.err(Collections.singletonList(resolveResult.unwrapErr()));
        }

        List<Path> dependencies = resolveResult.unwrap();

        // The model assembler factory is used to get assemblers that already have the correct
        // dependencies resolved for future loads
        Supplier<ModelAssembler> assemblerFactory;
        try {
            assemblerFactory = createModelAssemblerFactory(dependencies);
        } catch (MalformedURLException e) {
            return Result.err(List.of(e));
        }

        ModelAssembler assembler = assemblerFactory.get();

        // Note: The model assembler can handle loading all smithy files in a directory, so there's some potential
        //  here for inconsistent behavior.
        List<Path> allSmithyFilePaths = collectAllSmithyPaths(root, config.sources(), config.imports());

        Result<ValidatedResult<Model>, Exception> loadModelResult = loadModel(state, allSmithyFilePaths, assembler);
        // TODO: Assembler can fail if a file is not found. We can be more intelligent about
        //  handling this case to allow partially loading the project, but we will need to
        //  collect and report the errors somehow. For now, using collectAllSmithyPaths skips
        //  any files that don't exist, so we're essentially side-stepping the issue by
        //  coincidence.
        if (loadModelResult.isErr()) {
            return Result.err(Collections.singletonList(loadModelResult.unwrapErr()));
        }

        ValidatedResult<Model> modelResult = loadModelResult.unwrap();
        var definedShapesByFile = computeDefinedShapesByFile(allSmithyFilePaths, modelResult);
        var smithyFiles = createSmithyFiles(definedShapesByFile, (filePath) -> {
            // NOTE: isSmithyJarFile and isJarFile typically take in a URI (filePath is a path), but
            // the model stores jar paths as URIs
            if (LspAdapter.isSmithyJarFile(filePath) || LspAdapter.isJarFile(filePath)) {
                // Technically this can throw
                return Document.of(IoUtils.readUtf8Url(LspAdapter.jarUrl(filePath)));
            }
            // TODO: We recompute uri from path and vice-versa very frequently,
            //  maybe we can cache it.
            String uri = LspAdapter.toUri(filePath);
            Document managed = state.getManagedDocument(uri);
            if (managed != null) {
                return managed;
            }
            // There may be a more efficient way of reading this
            return Document.of(IoUtils.readUtf8File(filePath));
        });

        return Result.ok(new Project(root,
                config,
                dependencies,
                smithyFiles,
                assemblerFactory,
                Project.Type.NORMAL,
                modelResult,
                Project.RebuildIndex.create(modelResult)));
    }

    private static Result<ValidatedResult<Model>, Exception> loadModel(
            ServerState state,
            List<Path> models,
            ModelAssembler assembler
    ) {
        try {
            for (Path path : models) {
                Document managed = state.getManagedDocument(path);
                if (managed != null) {
                    assembler.addUnparsedModel(path.toString(), managed.copyText());
                } else {
                    assembler.addImport(path);
                }
            }

            return Result.ok(assembler.assemble());
        } catch (Exception e) {
            return Result.err(e);
        }
    }

    static Result<Project, List<Exception>> load(Path root) {
        return load(root, new ServerState());
    }

    private static Map<String, Set<ToShapeId>> computeDefinedShapesByFile(
            List<Path> allSmithyFilePaths,
            ValidatedResult<Model> modelResult
    ) {
        Map<String, Set<ToShapeId>> definedShapesByFile = modelResult.getResult().map(Model::shapes)
                .orElseGet(Stream::empty)
                .collect(Collectors.groupingByConcurrent(
                        shape -> shape.getSourceLocation().getFilename(), Collectors.toSet()));

        // There may be smithy files part of the project that aren't part of the model, e.g. empty files
        for (Path smithyFilePath : allSmithyFilePaths) {
            String pathString = smithyFilePath.toString();
            definedShapesByFile.putIfAbsent(pathString, Set.of());
        }

        return definedShapesByFile;
    }

    private static Map<String, SmithyFile> createSmithyFiles(
            Map<String, Set<ToShapeId>> definedShapesByFile,
            Function<String, Document> documentProvider
    ) {
        Map<String, SmithyFile> smithyFiles = new HashMap<>(definedShapesByFile.size());

        for (String path : definedShapesByFile.keySet()) {
            Document document = documentProvider.apply(path);
            SmithyFile smithyFile = SmithyFile.create(path, document);
            smithyFiles.put(path, smithyFile);
        }

        return smithyFiles;
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
