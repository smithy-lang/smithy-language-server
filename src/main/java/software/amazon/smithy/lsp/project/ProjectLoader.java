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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.document.DocumentNamespace;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentShape;
import software.amazon.smithy.lsp.document.DocumentVersion;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.loader.ModelDiscovery;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;

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
     * Loads a detached (single-file) {@link Project} with the given file.
     *
     * <p>Unlike {@link #load(Path, ProjectManager, Set)}, this method isn't
     * fallible since it doesn't do any IO that we would want to recover an
     * error from.
     *
     * @param uri URI of the file to load into a project
     * @param text Text of the file to load into a project
     * @return The loaded project
     */
    public static Project loadDetached(String uri, String text) {
        LOGGER.info("Loading detached project at " + uri);
        String asPath = LspAdapter.toPath(uri);
        ValidatedResult<Model> modelResult = Model.assembler()
                .addUnparsedModel(asPath, text)
                .assemble();

        Path path = Paths.get(asPath);
        List<Path> sources = Collections.singletonList(path);

        Project.Builder builder = Project.builder()
                .root(path.getParent())
                .config(ProjectConfig.builder()
                        .sources(Collections.singletonList(asPath))
                        .build())
                .modelResult(modelResult);

        Map<String, SmithyFile> smithyFiles = computeSmithyFiles(sources, modelResult, (filePath) -> {
            // NOTE: isSmithyJarFile and isJarFile typically take in a URI (filePath is a path), but
            // the model stores jar paths as URIs
            if (LspAdapter.isSmithyJarFile(filePath) || LspAdapter.isJarFile(filePath)) {
                return Document.of(IoUtils.readUtf8Url(LspAdapter.jarUrl(filePath)));
            } else if (filePath.equals(asPath)) {
                Document document = Document.of(text);
                return document;
            } else {
                // TODO: Make generic 'please file a bug report' exception
                throw new IllegalStateException(
                        "Attempted to load an unknown source file ("
                        + filePath + ") in detached project at "
                        + asPath + ". This is a bug in the language server.");
            }
        });

        return builder.smithyFiles(smithyFiles)
                .perFileMetadata(computePerFileMetadata(modelResult))
                .build();
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
     * @param projects Currently loaded projects, for getting content of managed documents
     * @param managedDocuments URIs of documents managed by the client
     * @return Result of loading the project
     */
    public static Result<Project, List<Exception>> load(
            Path root,
            ProjectManager projects,
            Set<String> managedDocuments
    ) {
        Result<ProjectConfig, List<Exception>> configResult = ProjectConfigLoader.loadFromRoot(root);
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
        Result<Supplier<ModelAssembler>, Exception> assemblerFactoryResult = createModelAssemblerFactory(dependencies);
        if (assemblerFactoryResult.isErr()) {
            return Result.err(Collections.singletonList(assemblerFactoryResult.unwrapErr()));
        }

        Supplier<ModelAssembler> assemblerFactory = assemblerFactoryResult.unwrap();
        ModelAssembler assembler = assemblerFactory.get();

        // Note: The model assembler can handle loading all smithy files in a directory, so there's some potential
        //  here for inconsistent behavior.
        List<Path> allSmithyFilePaths = collectAllSmithyPaths(root, config.sources(), config.imports());

        Result<ValidatedResult<Model>, Exception> loadModelResult = Result.ofFallible(() -> {
            for (Path path : allSmithyFilePaths) {
                if (!managedDocuments.isEmpty()) {
                    String pathString = path.toString();
                    String uri = LspAdapter.toUri(pathString);
                    if (managedDocuments.contains(uri)) {
                        assembler.addUnparsedModel(pathString, projects.getDocument(uri).copyText());
                    } else {
                        assembler.addImport(path);
                    }
                } else {
                    assembler.addImport(path);
                }
            }

            return assembler.assemble();
        });
        // TODO: Assembler can fail if a file is not found. We can be more intelligent about
        //  handling this case to allow partially loading the project, but we will need to
        //  collect and report the errors somehow. For now, using collectAllSmithyPaths skips
        //  any files that don't exist, so we're essentially side-stepping the issue by
        //  coincidence.
        if (loadModelResult.isErr()) {
            return Result.err(Collections.singletonList(loadModelResult.unwrapErr()));
        }

        ValidatedResult<Model> modelResult = loadModelResult.unwrap();

        Project.Builder projectBuilder = Project.builder()
                .root(root)
                .config(config)
                .dependencies(dependencies)
                .modelResult(modelResult)
                .assemblerFactory(assemblerFactory);

        Map<String, SmithyFile> smithyFiles = computeSmithyFiles(allSmithyFilePaths, modelResult, (filePath) -> {
            // NOTE: isSmithyJarFile and isJarFile typically take in a URI (filePath is a path), but
            // the model stores jar paths as URIs
            if (LspAdapter.isSmithyJarFile(filePath) || LspAdapter.isJarFile(filePath)) {
                // Technically this can throw
                return Document.of(IoUtils.readUtf8Url(LspAdapter.jarUrl(filePath)));
            }
            // TODO: We recompute uri from path and vice-versa very frequently,
            //  maybe we can cache it.
            String uri = LspAdapter.toUri(filePath);
            if (managedDocuments.contains(uri)) {
                return projects.getDocument(uri);
            }
            // There may be a more efficient way of reading this
            return Document.of(IoUtils.readUtf8File(filePath));
        });

        return Result.ok(projectBuilder.smithyFiles(smithyFiles)
                .perFileMetadata(computePerFileMetadata(modelResult))
                .smithyFileDependenciesIndex(SmithyFileDependenciesIndex.compute(modelResult))
                .build());
    }

    static Result<Project, List<Exception>> load(Path root) {
        return load(root, new ProjectManager(), new HashSet<>(0));
    }

    private static Map<String, SmithyFile> computeSmithyFiles(
            List<Path> allSmithyFilePaths,
            ValidatedResult<Model> modelResult,
            Function<String, Document> documentProvider
    ) {
        Map<String, Set<Shape>> shapesByFile;
        if (modelResult.getResult().isPresent()) {
            Model model = modelResult.getResult().get();
            shapesByFile = model.shapes().collect(Collectors.groupingByConcurrent(
                    shape -> shape.getSourceLocation().getFilename(), Collectors.toSet()));
        } else {
            shapesByFile = new HashMap<>(allSmithyFilePaths.size());
        }

        // There may be smithy files part of the project that aren't part of the model
        for (Path smithyFilePath : allSmithyFilePaths) {
            String pathString = smithyFilePath.toString();
            if (!shapesByFile.containsKey(pathString)) {
                shapesByFile.put(pathString, Collections.emptySet());
            }
        }

        Map<String, SmithyFile> smithyFiles = new HashMap<>(allSmithyFilePaths.size());
        for (Map.Entry<String, Set<Shape>> shapesByFileEntry : shapesByFile.entrySet()) {
            String path = shapesByFileEntry.getKey();
            Document document = documentProvider.apply(path);
            Set<Shape> fileShapes = shapesByFileEntry.getValue();
            SmithyFile smithyFile = buildSmithyFile(path, document, fileShapes).build();
            smithyFiles.put(path, smithyFile);
        }

        return smithyFiles;
    }

    /**
     * Computes extra information about what is in the Smithy file and where,
     * such as the namespace, imports, version number, and shapes.
     *
     * @param path Path of the Smithy file
     * @param document The document backing the Smithy file
     * @param shapes The shapes defined in the Smithy file
     * @return A builder for the Smithy file
     */
    public static SmithyFile.Builder buildSmithyFile(String path, Document document, Set<Shape> shapes) {
        DocumentParser documentParser = DocumentParser.forDocument(document);
        DocumentNamespace namespace = documentParser.documentNamespace();
        DocumentImports imports = documentParser.documentImports();
        Map<Position, DocumentShape> documentShapes = documentParser.documentShapes(shapes);
        DocumentVersion documentVersion = documentParser.documentVersion();
        return SmithyFile.builder()
                .path(path)
                .document(document)
                .shapes(shapes)
                .namespace(namespace)
                .imports(imports)
                .documentShapes(documentShapes)
                .documentVersion(documentVersion);
    }

    // This is gross, but necessary to deal with the way that array metadata gets merged.
    // When we try to reload a single file, we need to make sure we remove the metadata for
    // that file. But if there's array metadata, a single key contains merged elements from
    // other files. This splits up the metadata by source file, creating an artificial array
    // node for elements that are merged.
    //
    // This definitely has the potential to cause a performance hit if there's a huge amount
    // of metadata, since we are recomputing this on every change.
    static Map<String, Map<String, Node>> computePerFileMetadata(ValidatedResult<Model> modelResult) {
        Map<String, Node> metadata = modelResult.getResult().map(Model::getMetadata).orElse(new HashMap<>(0));
        Map<String, Map<String, Node>> perFileMetadata = new HashMap<>();
        for (Map.Entry<String, Node> entry : metadata.entrySet()) {
            if (entry.getValue().isArrayNode()) {
                Map<String, ArrayNode.Builder> arrayByFile = new HashMap<>();
                for (Node node : entry.getValue().expectArrayNode()) {
                    String filename = node.getSourceLocation().getFilename();
                    arrayByFile.computeIfAbsent(filename, (f) -> ArrayNode.builder()).withValue(node);
                }
                for (Map.Entry<String, ArrayNode.Builder> arrayByFileEntry : arrayByFile.entrySet()) {
                    perFileMetadata.computeIfAbsent(arrayByFileEntry.getKey(), (f) -> new HashMap<>())
                            .put(entry.getKey(), arrayByFileEntry.getValue().build());
                }
            } else {
                String filename = entry.getValue().getSourceLocation().getFilename();
                perFileMetadata.computeIfAbsent(filename, (f) -> new HashMap<>())
                        .put(entry.getKey(), entry.getValue());
            }
        }
        return perFileMetadata;
    }

    private static Result<Supplier<ModelAssembler>, Exception> createModelAssemblerFactory(List<Path> dependencies) {
        // We don't want the model to be broken when there are unknown traits,
        // because that will essentially disable language server features, so
        // we need to allow unknown traits for each factory.

        // TODO: There's almost certainly a better way to to this
        if (dependencies.isEmpty()) {
            return Result.ok(() -> Model.assembler().putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true));
        }

        Result<URLClassLoader, Exception> result = createDependenciesClassLoader(dependencies);
        if (result.isErr()) {
            return Result.err(result.unwrapErr());
        }
        return Result.ok(() -> {
            URLClassLoader classLoader = result.unwrap();
            return Model.assembler(classLoader)
                    .discoverModels(classLoader)
                    .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true);
        });
    }

    private static Result<URLClassLoader, Exception> createDependenciesClassLoader(List<Path> dependencies) {
        // Taken (roughly) from smithy-ci IsolatedRunnable
        try {
            URL[] urls = new URL[dependencies.size()];
            int i = 0;
            for (Path dependency : dependencies) {
                urls[i++] = dependency.toUri().toURL();
            }
            return Result.ok(new URLClassLoader(urls));
        } catch (MalformedURLException e) {
            return Result.err(e);
        }
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
