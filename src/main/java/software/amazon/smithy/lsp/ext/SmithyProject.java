/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.EnvironmentVariable;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.FileCacheResolver;
import software.amazon.smithy.cli.dependencies.MavenDependencyResolver;
import software.amazon.smithy.lsp.SmithyInterface;
import software.amazon.smithy.lsp.Utils;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidatedResultException;
import software.amazon.smithy.utils.IoUtils;

public final class SmithyProject {
    private static final MavenRepository CENTRAL = MavenRepository.builder()
            .url("https://repo.maven.apache.org/maven2")
            .build();
    private final File root;
    private final List<Path> imports;
    private final List<File> smithyFiles;
    private final List<File> externalJars;
    private final Map<URI, String> modelFiles;
    private final ValidatedResult<Model> model;
    private final Map<ShapeId, Location> locations;
    private final List<String> errors;

    private SmithyProject(
            File root,
            List<Path> imports,
            List<File> smithyFiles,
            List<File> externalJars,
            Map<URI, String> modelFiles,
            ValidatedResult<Model> model,
            Map<ShapeId, Location> locations,
            List<String> errors
    ) {
        this.root = root;
        this.imports = imports;
        this.smithyFiles = smithyFiles;
        this.externalJars = externalJars;
        this.modelFiles = modelFiles;
        this.model = model;
        this.locations = locations;
        this.errors = errors;
    }

    /**
     * Loads the Smithy project in the given directory.
     *
     * @param root Directory to load project from.
     * @return The loaded Smithy project.
     */
    public static SmithyProject forDirectory(File root) {
        SmithyBuildExtensions.Builder builder = SmithyBuildExtensions.builder();
        List<String> errors = new ArrayList<>();

        for (String filename: Constants.BUILD_FILES) {
            File smithyBuild = Paths.get(root.getAbsolutePath(), filename).toFile();
            if (smithyBuild.isFile()) {
                try {
                    SmithyBuildExtensions local = SmithyBuildLoader.load(smithyBuild.toPath());
                    builder.merge(local);
                    LspLog.println("Loaded smithy-build config" + local + " from " + smithyBuild.getAbsolutePath());
                } catch (Exception e) {
                    errors.add("Failed to load config from" + smithyBuild + ": " + e);
                }
            }
        }

        if (!errors.isEmpty()) {
            return new SmithyProject(
                    root,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new ArrayList<>(),
                    new HashMap<>(),
                    ValidatedResult.empty(),
                    new HashMap<>(),
                    errors);
        }

        SmithyBuildExtensions smithyBuild = builder.build();
        DependencyResolver resolver = createDependencyResolver(root, smithyBuild.getLastModifiedInMillis());
        return load(smithyBuild, root, resolver);
    }

    private static DependencyResolver createDependencyResolver(File root, long lastModified) {
        Path buildPath = Paths.get(root.toString(), "build", "smithy");
        File buildDir = new File(buildPath.toString());
        if (!buildDir.exists()) {
            buildDir.mkdirs();
        }
        Path cachePath = Paths.get(buildPath.toString(), "classpath.json");
        File dependencyCache = new File(cachePath.toString());
        if (!dependencyCache.exists()) {
            try {
                Files.createFile(cachePath);
            } catch (IOException e) {
                LspLog.println("Could not create dependency cache file " + e);
            }
        }
        MavenDependencyResolver delegate = new MavenDependencyResolver();
        return new FileCacheResolver(dependencyCache, lastModified, delegate);
    }

    /**
     * Reload the model.
     *
     * @return The loaded project.
     */
    public SmithyProject reload() {
        return load(
                SmithyInterface.readModel(smithyFiles, externalJars),
                this.imports,
                onlyExistingFiles(this.smithyFiles),
                this.externalJars,
                this.root,
                this.modelFiles
        );
    }

    /**
     * Reloads the project with changes for a specific file. This may be used
     * to add new files to the project.
     *
     * @param changedUri URI of the changed file.
     * @param contents Contents of the changed file.
     * @return The reloaded project.
     */
    public SmithyProject reloadWithChanges(URI changedUri, String contents) {
        this.modelFiles.put(changedUri, contents);
        // Reload the model using in-memory versions of source files
        List<File> existingSourceFiles = onlyExistingFiles(this.smithyFiles);
        // Handle the case when the file is new
        File changedFile = new File(changedUri);
        if (!existingSourceFiles.contains(changedFile)) {
            existingSourceFiles.add(changedFile);
        }
        Map<String, String> sources = new HashMap<>();
        for (File sourceFile : existingSourceFiles) {
            URI uri = sourceFile.toURI();
            sources.put(Paths.get(uri).toString(), this.modelFiles.get(uri));
        }

        return load(
                SmithyInterface.readModel(sources, this.externalJars),
                this.imports,
                existingSourceFiles,
                this.externalJars,
                this.root,
                this.modelFiles
        );
    }

    /**
     * Load the project using a SmithyBuildExtensions configuration and workspace
     * root.
     *
     * @param config configuration.
     * @param root workspace root.
     * @return either an error or a loaded project.
     */
    static SmithyProject load(SmithyBuildExtensions config, File root, DependencyResolver resolver) {
        List<Path> imports = config.getImports().stream()
                .map(p -> Paths.get(root.getAbsolutePath(), p).normalize())
                .collect(Collectors.toList());

        if (imports.isEmpty()) {
            imports.add(root.toPath());
        }

        LspLog.println("Imports from config: " + imports + " will be resolved against root " + root);

        List<File> smithyFiles = discoverSmithyFiles(imports, root);
        LspLog.println("Discovered smithy files: " + smithyFiles);

        List<File> externalJars = downloadExternalDependencies(config, resolver);
        LspLog.println("Downloaded external jars: " + externalJars);

        Either<Exception, ValidatedResult<Model>> readModelResult = SmithyInterface.readModel(smithyFiles,
                externalJars);

        Map<URI, String> modelFiles;
        if (readModelResult.isRight()) {
            modelFiles = readModelFiles(smithyFiles, readModelResult.getRight());
        } else {
            modelFiles = new HashMap<>();
        }
        return load(readModelResult, imports, smithyFiles, externalJars, root, modelFiles);
    }

    private static SmithyProject load(
            Either<Exception, ValidatedResult<Model>> loadModelResult,
            List<Path> imports,
            List<File> smithyFiles,
            List<File> externalJars,
            File root,
            Map<URI, String> modelFiles
    ) {
        List<String> errors = new ArrayList<>();
        Map<ShapeId, Location> definitionLocations = new HashMap<>();
        ValidatedResult<Model> model = ValidatedResult.empty();
        if (loadModelResult.isRight()) {
            model = loadModelResult.getRight();
            model.getValidationEvents().forEach(LspLog::println);
            // TODO: This shouldn't fail, it's only here because location collection is buggy.
            try {
                definitionLocations = collectLocations(model);
            } catch (Exception e) {
                errors.add("Failed to collect definition locations:\n" + e);
            }
        } else {
            errors.add(loadModelResult.getLeft().toString());
        }
        return new SmithyProject(
                root,
                imports,
                smithyFiles,
                externalJars,
                modelFiles,
                model,
                definitionLocations,
                errors
        );
    }

    static Map<ShapeId, Location> collectLocations(ValidatedResult<Model> model) {
        if (model.getResult().isPresent()) {
            ShapeLocationCollector collector = new FileCachingCollector();
            return collector.collectDefinitionLocations(model.getResult().get());
        }
        return new HashMap<>();
    }

    /**
     * @return The result of loading the Smithy model for this project.
     */
    public ValidatedResult<Model> getModel() {
        return this.model;
    }

    /**
     * @return The list of jars downloaded from dependencies which were used
     * to load this project.
     */
    public List<File> getExternalJars() {
        return this.externalJars;
    }

    /**
     * @return The list of *.smithy files used to load this project, which
     * were discovered by walking all subdirectories of the root of the project.
     */
    public List<File> getSmithyFiles() {
        return this.smithyFiles;
    }

    /**
     * Gets a map of URIs to file contents for all loaded Smithy model files,
     * including model files located in JARs.
     *
     * @return Map of URI to file contents.
     */
    public Map<URI, String> getModelFiles() {
        return this.modelFiles;
    }

    /**
     * @return A map of all the {@link ShapeId} in the loaded model to their
     * respective {@link Location}.
     */
    public Map<ShapeId, Location> getLocations() {
        return this.locations;
    }

    /**
     * @return The root directory of this project.
     */
    public File getRoot() {
        return this.root;
    }

    /**
     * Gets a list of the errors that occurred when trying to load the
     * model, <b>excluding</b> model validation errors.
     *
     * @see SmithyProject#isBroken()
     *
     * @return List of errors.
     */
    public List<String> getErrors() {
        return this.errors;
    }

    /**
     * {@link SmithyProject} is considered to be broken if any exception
     * that isn't a result of a model validation error occurs when the
     * language server attempted to load the model.
     *
     * <p>For example, a project with an invalid smithy-build.json is
     * considered to be broken.
     *
     * @see SmithyProject#getErrors()
     *
     * @return Whether the project is broken
     */
    public boolean isBroken() {
        return this.errors.size() > 0;
    }

    /**
     * Run a selector expression against the loaded model in the workspace.
     * @param expression the selector expression.
     * @return list of locations of shapes that match expression.
     */
    public Either<Exception, List<Location>> runSelector(String expression) {
        try {
            Selector selector = Selector.parse(expression);
            Set<Shape> shapes = selector.select(this.model.unwrap());
            return Either.forRight(shapes.stream()
                    .map(shape -> this.locations.get(shape.getId()))
                    .collect(Collectors.toList()));
        } catch (ValidatedResultException e) {
            return Either.forLeft(e);
        }
    }

    /**
     * Returns the shapeId of the shape that corresponds to the file uri and position within the model.
     *
     * @param uri String uri of model file.
     * @param position Cursor position within model file.
     * @return ShapeId of corresponding shape defined at location.
     */
    public Optional<ShapeId> getShapeIdFromLocation(URI uri, Position position) {
        Comparator<Map.Entry<ShapeId, Location>> rangeSize = Comparator.comparing(entry ->
                entry.getValue().getRange().getEnd().getLine() - entry.getValue().getRange().getStart().getLine());
        return locations.entrySet().stream()
                .filter(entry -> entry.getValue().getUri().endsWith(uri.getPath()))
                .filter(entry -> isPositionInRange(entry.getValue().getRange(), position))
                // Since the position is in each of the overlapping shapes, return the location with the smallest range.
                .sorted(rangeSize)
                .map(Map.Entry::getKey)
                .findFirst();
    }

    private boolean isPositionInRange(Range range, Position position) {
        if (range.getStart().getLine() > position.getLine()) {
            return false;
        }
        if (range.getEnd().getLine() < position.getLine()) {
            return false;
        }
        // For single-line ranges, make sure position is between start and end chars.
        if (range.getStart().getLine() == position.getLine()
                && range.getEnd().getLine() == position.getLine()) {
            return (range.getStart().getCharacter() <= position.getCharacter()
                    && range.getEnd().getCharacter() >= position.getCharacter());
        } else if (range.getStart().getLine() == position.getLine()) {
            return range.getStart().getCharacter() <= position.getCharacter();
        } else if (range.getEnd().getLine() == position.getLine()) {
            return range.getEnd().getCharacter() >= position.getCharacter();
        }
        return true;
    }

    private static Boolean isValidSmithyFile(Path file) {
        String fName = file.getFileName().toString();
        return fName.endsWith(Constants.SMITHY_EXTENSION);
    }

    private static List<File> walkSmithyFolder(Path path, File root) {
        try (Stream<Path> walk = Files.walk(path)) {
            return walk.filter(Files::isRegularFile)
                    .filter(SmithyProject::isValidSmithyFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            LspLog.println("Failed to walk import '" + path + "' from root " + root + ": " + e);
            return new ArrayList<>();
        }
    }

    private static List<File> discoverSmithyFiles(List<Path> imports, File root) {
        List<File> smithyFiles = new ArrayList<>();
        imports.forEach(path -> {
            if (Files.isDirectory(path)) {
                smithyFiles.addAll(walkSmithyFolder(path, root));
            } else if (isValidSmithyFile(path)) {
                smithyFiles.add(path.resolve(root.toPath()).toFile());
            }
        });
        return smithyFiles;
    }

    private static List<File> downloadExternalDependencies(SmithyBuildExtensions extensions,
                                                           DependencyResolver resolver) {
        LspLog.println("Downloading external dependencies for " + extensions);
        try {
            addConfiguredMavenRepos(extensions, resolver);
            extensions.getMavenConfig().getDependencies().forEach(resolver::addDependency);

            return resolver.resolve().stream()
                    .map(artifact -> artifact.getPath().toFile()).collect(Collectors.toList());
        } catch (Exception e) {
            LspLog.println("Failed to download external jars for " + extensions + ": " + e);
            return Collections.emptyList();
        }
    }

    private static void addConfiguredMavenRepos(SmithyBuildExtensions extensions, DependencyResolver resolver) {
        // Environment variables take precedence over config files.
        String envRepos = EnvironmentVariable.SMITHY_MAVEN_REPOS.get();
        if (envRepos != null) {
            for (String repo : envRepos.split("\\|")) {
                resolver.addRepository(MavenRepository.builder().url(repo.trim()).build());
            }
        }

        Set<MavenRepository> configuredRepos = extensions.getMavenConfig().getRepositories();

        if (!configuredRepos.isEmpty()) {
            configuredRepos.forEach(resolver::addRepository);
        } else if (envRepos == null) {
            LspLog.println(String.format("maven.repositories is not defined in smithy-build.json and the %s "
                            + "environment variable is not set. Defaulting to Maven Central.",
                    EnvironmentVariable.SMITHY_MAVEN_REPOS));
            resolver.addRepository(CENTRAL);
        }
    }

    private static List<File> onlyExistingFiles(List<File> files) {
        return files.stream().filter(File::isFile).collect(Collectors.toList());
    }

    private static Map<URI, String> readModelFiles(List<File> sources, ValidatedResult<Model> model) {
        Set<URI> modelFilesUris = sources.stream()
                .map(File::getAbsolutePath)
                .map(Utils::createUri)
                .collect(Collectors.toSet());
        Set<URI> loadedModelFileUris = model.getResult()
                .map(Model::shapes)
                .orElse(Stream.empty())
                .map(Shape::getSourceLocation)
                .map(SourceLocation::getFilename)
                .map(Utils::createUri)
                .collect(Collectors.toSet());
        modelFilesUris.addAll(loadedModelFileUris);
        Map<URI, String> modelFiles = new HashMap<>();
        for (URI uri : modelFilesUris) {
            String contents;
            if (Utils.isSmithyJarFile(uri.toString())) {
                contents = Utils.readJarFile(uri.toString());
            } else {
                contents = IoUtils.readUtf8File(Paths.get(uri).toString());
            }
            modelFiles.put(uri, contents);
        }
        return modelFiles;
    }
}
