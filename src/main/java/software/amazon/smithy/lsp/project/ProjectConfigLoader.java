/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.cli.EnvironmentVariable;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.DependencyResolverException;
import software.amazon.smithy.cli.dependencies.MavenDependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.model.SourceException;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.loader.ModelDiscovery;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Loads {@link ProjectConfig}s from {@link BuildFiles}.
 */
final class ProjectConfigLoader {
    private static final NodeMapper NODE_MAPPER = new NodeMapper();
    private static final BuildFileType[] EXTS = {BuildFileType.SMITHY_BUILD_EXT_0, BuildFileType.SMITHY_BUILD_EXT_1};

    private final BuildFiles buildFiles;
    private final List<ValidationEvent> events = new ArrayList<>();
    private final Map<BuildFileType, Node> smithyNodes = new HashMap<>(BuildFileType.values().length);

    private ProjectConfigLoader(BuildFiles buildFiles) {
        this.buildFiles = buildFiles;
    }

    /**
     * Runs structural validation on each of the given {@link BuildFiles},
     * without performing dependency resolution or constructing a new
     * {@link ProjectConfig}.
     *
     * @param buildFiles The build files to validate
     * @return The list of validation events
     */
    static List<ValidationEvent> validateBuildFiles(BuildFiles buildFiles) {
        List<ValidationEvent> events = new ArrayList<>();
        for (BuildFile buildFile : buildFiles) {
            LoadBuildFile<?> loader = switch (buildFile.type()) {
                case SMITHY_BUILD -> LoadBuildFile.LOAD_SMITHY_BUILD;
                case SMITHY_BUILD_EXT_0, SMITHY_BUILD_EXT_1 -> LoadBuildFile.LOAD_BUILD_EXT;
                case SMITHY_PROJECT -> LoadBuildFile.LOAD_SMITHY_PROJECT;
            };

            loadFile(buildFile, loader, events::add, (type, node) -> {
            });
        }
        return events;
    }

    /**
     * Result of loading the config. Used in place of {@link ValidatedResult}
     * because its value may not be present, which we don't want here.
     *
     * @param config The loaded config, non-nullable
     * @param events The events that occurred during loading, non-nullable
     */
    record Result(ProjectConfig config, List<ValidationEvent> events) {}

    /**
     * Loads a project's config from the given {@link BuildFiles}, resolving
     * dependencies using the default Maven dependency resolver.
     *
     * @param root The root of the project whose config is being loaded
     * @param buildFiles The build files to load config from
     * @return The result of loading the config
     */
    static Result load(Path root, BuildFiles buildFiles) {
        return load(root, buildFiles, Resolver.DEFAULT_RESOLVER_FACTORY);
    }

    /**
     * Loads a project's config from the given {@link BuildFiles}, resolving
     * dependencies using the given factory.
     *
     * @param root The root of the project whose config is being loaded
     * @param buildFiles The build files to load config from
     * @param dependencyResolverFactory A factory to get the Maven dependency
     *                                  resolver to use
     * @return The result of loading the config
     */
    static Result load(Path root, BuildFiles buildFiles, Supplier<DependencyResolver> dependencyResolverFactory) {
        var loader = new ProjectConfigLoader(buildFiles);
        SmithyBuildConfig smithyBuildConfig = loader.loadSmithyBuild();
        SmithyBuildExtensions.Builder extBuilder = loader.loadExts();
        SmithyBuildConfig merged = loader.mergeSmithyBuildConfig(smithyBuildConfig, extBuilder);
        SmithyProjectJson smithyProjectJson = loader.loadSmithyProject();

        List<String> sources = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        MavenConfig mavenConfig = null;
        List<SmithyProjectJson.ProjectDependency> projectDependencies = new ArrayList<>();

        if (merged != null) {
            sources.addAll(merged.getSources());
            imports.addAll(merged.getImports());
            var mavenOpt = merged.getMaven();
            if (mavenOpt.isPresent()) {
                mavenConfig = mavenOpt.get();
            }
        }

        if (smithyProjectJson != null) {
            sources.addAll(smithyProjectJson.sources());
            imports.addAll(smithyProjectJson.imports());
            projectDependencies.addAll(smithyProjectJson.dependencies());
        }

        var resolver = new Resolver(root, loader.events, loader.smithyNodes, dependencyResolverFactory);
        ProjectConfig resolved = resolver.resolve(sources, imports, mavenConfig, projectDependencies);

        return new Result(resolved, resolver.events());
    }

    private SmithyBuildConfig loadSmithyBuild() {
        return loadFile(
                buildFiles.getByType(BuildFileType.SMITHY_BUILD),
                LoadBuildFile.LOAD_SMITHY_BUILD,
                events::add,
                smithyNodes::put
        );
    }

    private SmithyProjectJson loadSmithyProject() {
        return loadFile(
                buildFiles.getByType(BuildFileType.SMITHY_PROJECT),
                LoadBuildFile.LOAD_SMITHY_PROJECT,
                events::add,
                smithyNodes::put
        );
    }

    private SmithyBuildExtensions.Builder loadExts() {
        SmithyBuildExtensions.Builder extBuilder = null;
        for (BuildFileType extType : EXTS) {
            SmithyBuildExtensions ext = loadFile(
                    buildFiles.getByType(extType),
                    LoadBuildFile.LOAD_BUILD_EXT,
                    events::add,
                    smithyNodes::put
            );
            if (ext != null) {
                if (extBuilder == null) {
                    extBuilder = SmithyBuildExtensions.builder();
                }
                extBuilder.merge(ext);
            }
        }
        return extBuilder;
    }

    private static <T> T loadFile(
            BuildFile buildFile,
            LoadBuildFile<T> loadBuildFile,
            Consumer<ValidationEvent> eventConsumer,
            BiConsumer<BuildFileType, Node> nodeConsumer
    ) {
        if (buildFile == null) {
            return null;
        }

        ValidatedResult<Node> nodeResult = ToSmithyNode.toSmithyNode(buildFile);
        nodeResult.getValidationEvents().forEach(eventConsumer);
        Node smithyNode = nodeResult.getResult().orElse(null);
        if (smithyNode != null) {
            nodeConsumer.accept(buildFile.type(), smithyNode);
            try {
                return loadBuildFile.load(smithyNode);
            } catch (Exception e) {
                eventConsumer.accept(toEvent(e, buildFile));
            }
        }

        return null;
    }

    private SmithyBuildConfig mergeSmithyBuildConfig(
            SmithyBuildConfig smithyBuildConfig,
            SmithyBuildExtensions.Builder extBuilder
    ) {
        if (smithyBuildConfig == null && extBuilder == null) {
            return null;
        } else if (extBuilder == null) {
            return smithyBuildConfig;
        } else if (smithyBuildConfig == null) {
            try {
                return extBuilder.build().asSmithyBuildConfig();
            } catch (Exception e) {
                // Add the event to any ext file
                for (BuildFileType ext : EXTS) {
                    BuildFile buildFile = buildFiles.getByType(ext);
                    if (buildFile != null) {
                        events.add(toEvent(e, buildFile));
                        break;
                    }
                }
            }
        } else {
            try {
                var extConfig = extBuilder.build().asSmithyBuildConfig();
                return smithyBuildConfig.toBuilder().merge(extConfig).build();
            } catch (Exception e) {
                // Add the event to either smithy-build.json, or an ext file
                for (BuildFile buildFile : buildFiles) {
                    if (buildFile.type().supportsMavenConfiguration()) {
                        events.add(toEvent(e, buildFile));
                        break;
                    }
                }
            }
        }

        return null;
    }

    private static ValidationEvent toEvent(Exception e, BuildFile fallbackBuildFile) {
        // Most exceptions thrown will be from structural validation, i.e. is the Node in the expected format for the
        // build file. These exceptions will be SourceExceptions most likely, which are easy to map to a source
        // location.
        SourceException asSourceException = null;
        if (e instanceof SourceException sourceException) {
            asSourceException = sourceException;
        } else if (e.getCause() instanceof SourceException sourceException) {
            asSourceException = sourceException;
        }

        // If the source location is NONE, the filename won't map to any actual file so you won't see the error
        if (asSourceException != null && !SourceLocation.NONE.equals(asSourceException.getSourceLocation())) {
            return ValidationEvent.fromSourceException(asSourceException);
        }

        // Worst case, just put the error at the top of the file. If this happens enough that it is a problem, we
        // can revisit how this validation works, or manually map the specific cases.
        return ValidationEvent.builder()
                .id("SmithyBuildConfig")
                .severity(Severity.ERROR)
                .message(e.getMessage())
                .sourceLocation(new SourceLocation(fallbackBuildFile.path(), 1, 1))
                .build();
    }

    /**
     * Strategy for deserializing a {@link Node} into a {@code T}, differently
     * depending on {@link BuildFileType}.
     *
     * @param <T> The deserialized type
     */
    private interface LoadBuildFile<T> {
        LoadBuildFile<SmithyBuildConfig> LOAD_SMITHY_BUILD = SmithyBuildConfig::fromNode;

        LoadBuildFile<SmithyBuildExtensions> LOAD_BUILD_EXT = (node) -> {
            var config = NODE_MAPPER.deserialize(node, SmithyBuildExtensions.class);
            config.mergeMavenFromSmithyBuildConfig(SmithyBuildConfig.fromNode(node));
            return config;
        };

        LoadBuildFile<SmithyProjectJson> LOAD_SMITHY_PROJECT = SmithyProjectJson::fromNode;

        T load(Node node);
    }

    /**
     * Handles resolving dependencies, and finding all model paths that will be
     * loaded in the project. It also keeps track of any errors that occur in
     * this process, and tries to map them back to a specific location in a
     * build file so we can show a diagnostic.
     *
     * @param root The root of the project, used to resolve model paths
     * @param events The list to add any events that occur to
     * @param smithyNodes The loaded smithy nodes for each build file type,
     *                    used to map errors to a specific location
     * @param dependencyResolverFactory Provides the Maven dependency resolver
     *                                  implementation to use
     */
    private record Resolver(
            Path root,
            List<ValidationEvent> events,
            Map<BuildFileType, Node> smithyNodes,
            Supplier<DependencyResolver> dependencyResolverFactory
    ) {
        // Taken from smithy-cli ConfigurationUtils
        private static final Supplier<MavenRepository> CENTRAL = () -> MavenRepository.builder()
                .url("https://repo.maven.apache.org/maven2")
                .build();
        private static final Supplier<DependencyResolver> DEFAULT_RESOLVER_FACTORY = () ->
                new MavenDependencyResolver(EnvironmentVariable.SMITHY_MAVEN_CACHE.get());

        private ProjectConfig resolve(
                List<String> sources,
                List<String> imports,
                MavenConfig mavenConfig,
                List<SmithyProjectJson.ProjectDependency> projectDependencies
        ) {
            Set<Path> resolvedMaven = resolveMaven(mavenConfig);
            Set<Path> resolveProjectDependencies = resolveProjectDependencies(projectDependencies);

            List<URL> resolvedDependencies = new ArrayList<>();
            try {
                for (var dep : resolvedMaven) {
                    resolvedDependencies.add(dep.toUri().toURL());
                }
                for (var dep : resolveProjectDependencies) {
                    resolvedDependencies.add(dep.toUri().toURL());
                }
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }

            Set<Path> uniqueModelPaths = collectAllSmithyFilePaths(sources, imports);
            List<Path> modelPaths = new ArrayList<>(uniqueModelPaths);

            return new ProjectConfig(
                    sources,
                    imports,
                    projectDependencies,
                    mavenConfig,
                    modelPaths,
                    resolvedDependencies
            );
        }

        private Set<Path> resolveMaven(MavenConfig maven) {
            if (maven == null || (maven.getRepositories().isEmpty() && maven.getDependencies().isEmpty())) {
                return Set.of();
            }

            List<DependencyResolverException> exceptions = new ArrayList<>();
            DependencyResolver resolver = dependencyResolverFactory.get();

            Set<MavenRepository> repositories = getConfiguredMavenRepos(maven);
            for (MavenRepository repo : repositories) {
                try {
                    resolver.addRepository(repo);
                } catch (DependencyResolverException e) {
                    exceptions.add(e);
                }
            }

            for (String dependency : maven.getDependencies()) {
                try {
                    resolver.addDependency(dependency);
                } catch (DependencyResolverException e) {
                    exceptions.add(e);
                }
            }

            List<ResolvedArtifact> resolvedArtifacts;
            try {
                resolvedArtifacts = resolver.resolve();
            } catch (DependencyResolverException e) {
                exceptions.add(e);
                resolvedArtifacts = List.of();
            }

            handleDependencyResolverExceptions(exceptions);

            Set<Path> dependencyPaths = new HashSet<>(resolvedArtifacts.size());
            for (ResolvedArtifact resolvedArtifact : resolvedArtifacts) {
                Path path = resolvedArtifact.getPath().toAbsolutePath();
                if (!Files.exists(path)) {
                    throw new RuntimeException(String.format(
                            "Dependency was resolved (%s), but it was not found on disk at %s",
                            resolvedArtifact, path));
                }
                dependencyPaths.add(path);
            }

            return dependencyPaths;
        }

        // Taken from smithy-cli ConfigurationUtils::getConfiguredMavenRepos
        private static Set<MavenRepository> getConfiguredMavenRepos(MavenConfig config) {
            Set<MavenRepository> repositories = new LinkedHashSet<>();

            String envRepos = EnvironmentVariable.SMITHY_MAVEN_REPOS.get();
            if (envRepos != null) {
                for (String repo : envRepos.split("\\|")) {
                    repositories.add(MavenRepository.builder().url(repo.trim()).build());
                }
            }

            Set<MavenRepository> configuredRepos = config.getRepositories();

            if (!configuredRepos.isEmpty()) {
                repositories.addAll(configuredRepos);
            } else if (envRepos == null) {
                repositories.add(CENTRAL.get());
            }
            return repositories;
        }

        private void handleDependencyResolverExceptions(List<DependencyResolverException> exceptions) {
            if (exceptions.isEmpty()) {
                return;
            }

            StringBuilder builder = new StringBuilder();
            for (DependencyResolverException exception : exceptions) {
                builder.append(exception.getMessage()).append("\n");
            }
            String message = builder.toString();

            for (Node smithyNode : smithyNodes.values()) {
                if (!(smithyNode instanceof ObjectNode objectNode)) {
                    continue;
                }

                for (StringNode memberNameNode : objectNode.getMembers().keySet()) {
                    String memberName = memberNameNode.getValue();
                    if ("maven".equals(memberName)) {
                        events.add(ValidationEvent.builder()
                                .id("DependencyResolver")
                                .severity(Severity.ERROR)
                                .message("Dependency resolution failed: " + message)
                                .sourceLocation(memberNameNode)
                                .build());
                        break;
                    }
                }
            }
        }

        private Set<Path> resolveProjectDependencies(List<SmithyProjectJson.ProjectDependency> projectDependencies) {
            Set<String> notFoundDependencies = new HashSet<>();
            Set<Path> dependencies = new HashSet<>();

            for (var dependency : projectDependencies) {
                Path path = root.resolve(dependency.path()).normalize();
                if (!Files.exists(path)) {
                    notFoundDependencies.add(dependency.path());
                } else {
                    dependencies.add(path);
                }
            }

            handleNotFoundProjectDependencies(notFoundDependencies);

            return dependencies;
        }

        private void handleNotFoundProjectDependencies(Set<String> notFound) {
            if (notFound.isEmpty()) {
                return;
            }

            if (!(smithyNodes.get(BuildFileType.SMITHY_PROJECT) instanceof ObjectNode objectNode)) {
                return;
            }

            if (objectNode.getMember("dependencies").orElse(null) instanceof ArrayNode arrayNode) {
                for (Node elem : arrayNode) {
                    if (elem instanceof ObjectNode depNode
                        && depNode.getMember("path").orElse(null) instanceof StringNode depPathNode
                        && notFound.contains(depPathNode.getValue())) {
                        events.add(ValidationEvent.builder()
                                .id("FileNotFound")
                                .severity(Severity.ERROR)
                                .message("File not found")
                                .sourceLocation(depPathNode)
                                .build());
                    }
                }
            }
        }

        // sources and imports can contain directories or files, relative or absolute.
        // Note: The model assembler can handle loading all smithy files in a directory, so there's some potential
        //  here for inconsistent behavior.
        private Set<Path> collectAllSmithyFilePaths(List<String> sources, List<String> imports) {
            Set<String> notFound = new HashSet<>();
            Set<Path> paths = new HashSet<>();

            collectFilePaths(paths, sources, notFound);
            collectFilePaths(paths, imports, notFound);

            handleNotFoundSourcesAndImports(notFound);

            return paths;
        }

        private void collectFilePaths(Set<Path> accumulator, List<String> paths, Set<String> notFound) {
            for (String file : paths) {
                Path path = root.resolve(file).normalize();
                if (!Files.exists(path)) {
                    notFound.add(path.toString());
                } else {
                    collectDirectory(accumulator, root, path);
                }
            }
        }

        private void handleNotFoundSourcesAndImports(Set<String> notFound) {
            for (Node smithyNode : smithyNodes.values()) {
                if (!(smithyNode instanceof ObjectNode objectNode)) {
                    continue;
                }

                if (objectNode.getMember("sources").orElse(null) instanceof ArrayNode sourcesNode) {
                    addNotFoundEvents(sourcesNode, notFound);
                }

                if (objectNode.getMember("imports").orElse(null) instanceof ArrayNode importsNode) {
                    addNotFoundEvents(importsNode, notFound);
                }
            }
        }

        private void addNotFoundEvents(ArrayNode searchNode, Set<String> notFound) {
            for (Node elem : searchNode) {
                if (elem instanceof StringNode stringNode) {
                    String fullPath = root.resolve(stringNode.getValue()).normalize().toString();
                    if (notFound.contains(fullPath)) {
                        events.add(ValidationEvent.builder()
                                .id("FileNotFound")
                                .severity(Severity.ERROR)
                                .message("File not found: " + fullPath)
                                .sourceLocation(stringNode)
                                .build());
                    }
                }
            }
        }

        // All of this copied from smithy-build SourcesPlugin, except I changed the `accumulator` to
        // be a Collection instead of a list.
        private static void collectDirectory(Collection<Path> accumulator, Path root, Path current) {
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

        private static void collectJar(Collection<Path> accumulator, String jarRoot, Path jarPath) {
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

        private static void collectFile(Collection<Path> accumulator, Path target) {
            if (target == null) {
                return;
            }
            String filename = target.toString();
            if (filename.endsWith(".smithy") || filename.endsWith(".json")) {
                accumulator.add(target);
            }
        }
    }
}
