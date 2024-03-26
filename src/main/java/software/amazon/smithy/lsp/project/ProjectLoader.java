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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.document.DocumentNamespace;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentShape;
import software.amazon.smithy.lsp.document.DocumentVersion;
import software.amazon.smithy.lsp.protocol.UriAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.loader.ModelDiscovery;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;

/**
 * Loads {@link Project}s.
 */
public final class ProjectLoader {
    private static final Logger LOGGER = Logger.getLogger(ProjectLoader.class.getName());

    private ProjectLoader() {
    }

    /**
     * Loads a {@link Project} from a given root path.
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
     * @return Result of loading the project
     */
    public static Result<Project, List<Exception>> load(Path root) {
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

        // TODO: We need some default behavior for when no project files are specified, like running in
        //  'detached' mode or something
        List<Path> sources = config.getSources().stream().map(root::resolve).collect(Collectors.toList());
        List<Path> imports = config.getImports().stream().map(root::resolve).collect(Collectors.toList());

        // The model assembler factory is used to get assemblers that already have the correct
        // dependencies resolved for future loads
        Result<Supplier<ModelAssembler>, Exception> assemblerFactoryResult = createModelAssemblerFactory(dependencies);
        if (assemblerFactoryResult.isErr()) {
            return Result.err(Collections.singletonList(assemblerFactoryResult.unwrapErr()));
        }

        Supplier<ModelAssembler> assemblerFactory = assemblerFactoryResult.unwrap();
        ModelAssembler assembler = assemblerFactory.get();

        Result<ValidatedResult<Model>, Exception> loadModelResult = Result.ofFallible(() ->
                loadModel(assembler, sources, imports));
        // TODO: Assembler can fail if a file is not found. We can be more intelligent about
        //  handling this case to allow partially loading the project, but we will need to
        //  collect the errors somehow. For now, just fail
        if (loadModelResult.isErr()) {
            return Result.err(Collections.singletonList(loadModelResult.unwrapErr()));
        }

        ValidatedResult<Model> modelResult = loadModelResult.unwrap();

        Project.Builder projectBuilder = Project.builder()
                .root(root)
                .sources(sources)
                .imports(imports)
                .dependencies(dependencies)
                .modelResult(modelResult)
                .assemblerFactory(assemblerFactory);

        Map<String, Set<Shape>> shapes;
        if (modelResult.getResult().isPresent()) {
            Model model = modelResult.getResult().get();
            shapes = model.shapes().collect(Collectors.groupingByConcurrent(
                    shape -> shape.getSourceLocation().getFilename(), Collectors.toSet()));
        } else {
            shapes = new HashMap<>(0);
        }

        // There may be smithy files part of the project that aren't part of the model
        List<Path> allSmithyFilePaths = collectAllSmithyPaths(root, config.getSources(), config.getImports());
        for (Path path : allSmithyFilePaths) {
            if (!shapes.containsKey(path.toString())) {
                shapes.put(path.toString(), Collections.emptySet());
            }
        }

        Map<String, SmithyFile> smithyFiles = new HashMap<>(shapes.size());
        for (Map.Entry<String, Set<Shape>> entry : shapes.entrySet()) {
            String path = entry.getKey();
            Document document;
            if (UriAdapter.isSmithyJarFile(path) || UriAdapter.isJarFile(path)) {
                // Technically this can throw
                document = Document.of(IoUtils.readUtf8Url(UriAdapter.jarUrl(path)));
            } else {
                // There may be a more efficient way of reading this
                document = Document.of(IoUtils.readUtf8File(path));
            }
            Set<Shape> fileShapes = entry.getValue();
            SmithyFile smithyFile = buildSmithyFile(path, document, fileShapes).build();
            smithyFiles.put(path, smithyFile);
        }
        projectBuilder.smithyFiles(smithyFiles);

        return Result.ok(projectBuilder.build());
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

    private static ValidatedResult<Model> loadModel(ModelAssembler assembler, List<Path> sources, List<Path> imports) {
        for (Path path : sources) {
            assembler.addImport(path);
        }
        for (Path path : imports) {
            assembler.addImport(path);
        }

        return assembler.assemble();
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
            Path path = root.resolve(file);
            collectDirectory(paths, root, path);
        }
        for (String file : imports) {
            Path path = root.resolve(file);
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

    private static void collectJar(List<Path> accumulator, String jarRoot, Path jarPath) throws IOException {
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
