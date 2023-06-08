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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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
import software.amazon.smithy.lsp.SmithyInterface;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidatedResultException;

public final class SmithyProject {
    private static final MavenRepository CENTRAL = MavenRepository.builder()
            .url("https://repo.maven.apache.org/maven2")
            .build();
    private final List<Path> imports;
    private final List<File> smithyFiles;
    private final List<File> externalJars;
    private Map<ShapeId, Location> locations = Collections.emptyMap();
    private final ValidatedResult<Model> model;
    private final File root;

    SmithyProject(
            List<Path> imports,
            List<File> smithyFiles,
            List<File> externalJars,
            File root,
            ValidatedResult<Model> model
    ) {
        this.imports = imports;
        this.root = root;
        this.model = model;
        this.smithyFiles = smithyFiles;
        this.externalJars = externalJars;
        model.getResult().ifPresent(m -> this.locations = collectLocations(m));
    }

    /**
     * Recompile the model, adding a file to list of tracked files, potentially
     * excluding some other file.
     * <p>
     * This version of the method above is used when the
     * file is in ephemeral storage (temporary location when file is being changed)
     *
     * @param changed file which may or may not be already tracked by this project.
     * @param exclude file to exclude from being recompiled.
     * @return either an error, or a loaded project.
     */
    public Either<Exception, SmithyProject> recompile(File changed, File exclude) {
        HashSet<File> fileSet = new HashSet<>();

        for (File existing : onlyExistingFiles(this.smithyFiles)) {
            if (exclude != null && !existing.equals(exclude)) {
                fileSet.add(existing);
            }
        }

        if (changed.isFile()) {
            fileSet.add(changed);
        }

        return load(this.imports, new ArrayList<>(fileSet), this.externalJars, this.root);
    }

    public ValidatedResult<Model> getModel() {
        return this.model;
    }

    public List<File> getExternalJars() {
        return this.externalJars;
    }

    public List<File> getSmithyFiles() {
        return this.smithyFiles;
    }

    public List<SmithyCompletionItem> getCompletions(String token, boolean isTrait, Optional<ShapeId> target) {
        return this.model.getResult().map(model -> Completions.find(model, token, isTrait, target))
                .orElse(Collections.emptyList());
    }

    public Map<ShapeId, Location> getLocations() {
        return this.locations;
    }

    /**
     * Load the project using a SmithyBuildExtensions configuration and workspace
     * root.
     *
     * @param config configuration.
     * @param root workspace root.
     * @return either an error or a loaded project.
     */
    public static Either<Exception, SmithyProject> load(SmithyBuildExtensions config, File root,
                                                        DependencyResolver resolver) {
        List<Path> imports = config.getImports().stream().map(p -> Paths.get(root.getAbsolutePath(), p).normalize())
                .collect(Collectors.toList());

        if (imports.isEmpty()) {
            imports.add(root.toPath());
        }

        LspLog.println("Imports from config: " + imports + " will be resolved against root " + root);

        List<File> smithyFiles = discoverSmithyFiles(imports, root);
        LspLog.println("Discovered smithy files: " + smithyFiles);

        List<File> externalJars = downloadExternalDependencies(config, resolver);
        LspLog.println("Downloaded external jars: " + externalJars);

        return load(imports, smithyFiles, externalJars, root);

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

    private static Either<Exception, SmithyProject> load(
            List<Path> imports,
            List<File> smithyFiles,
            List<File> externalJars,
            File root
    ) {
        Either<Exception, ValidatedResult<Model>> model = createModel(smithyFiles, externalJars);

        if (model.isLeft()) {
            return Either.forLeft(model.getLeft());
        } else {
            model.getRight().getValidationEvents().forEach(LspLog::println);

            try {
                SmithyProject p = new SmithyProject(imports, smithyFiles, externalJars, root, model.getRight());
                return Either.forRight(p);
            } catch (Exception e) {
                return Either.forLeft(e);
            }
        }
    }

    private static Either<Exception, ValidatedResult<Model>> createModel(
            List<File> discoveredFiles,
            List<File> externalJars
    ) {
        return SmithyInterface.readModel(discoveredFiles, externalJars);
    }

    public File getRoot() {
        return this.root;
    }

    private static Map<ShapeId, Location> collectLocations(Model model) {
        ShapeLocationCollector collector = new FileCachingCollector();
        return collector.collectDefinitionLocations(model);
    }

    /**
     * Returns the shapeId of the shape that corresponds to the file uri and position within the model.
     *
     * @param uri String uri of model file.
     * @param position Cursor position within model file.
     * @return ShapeId of corresponding shape defined at location.
     */
    public Optional<ShapeId> getShapeIdFromLocation(String uri, Position position) {
        Comparator<Map.Entry<ShapeId, Location>> rangeSize = Comparator.comparing(entry ->
                entry.getValue().getRange().getEnd().getLine() - entry.getValue().getRange().getStart().getLine());
        return locations.entrySet().stream()
                .filter(entry -> entry.getValue().getUri().endsWith(Paths.get(uri).toString()))
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
            return walk.filter(Files::isRegularFile).filter(SmithyProject::isValidSmithyFile).map(Path::toFile)
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

    private static List<File> onlyExistingFiles(Collection<File> files) {
        return files.stream().filter(File::isFile).collect(Collectors.toList());
    }
}
