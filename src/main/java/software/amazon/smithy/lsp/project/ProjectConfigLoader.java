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
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Loads and validates {@link ProjectConfig}s from {@link BuildFiles}.
 */
final class ProjectConfigLoader {
    private interface BuildFileValidator<T> {
        T validate(BuildFile source, Node node, Consumer<ValidationEvent> eventConsumer);
    }

    private record SmithyBuildJsonValidator() implements BuildFileValidator<SmithyBuildConfig> {
        @Override
        public SmithyBuildConfig validate(BuildFile source, Node node, Consumer<ValidationEvent> eventConsumer) {
            try {
                return SmithyBuildConfig.fromNode(node);
            } catch (Exception e) {
                eventConsumer.accept(toEvent(e, source));
            }
            return null;
        }
    }

    private record SmithyBuildExtValidator() implements BuildFileValidator<SmithyBuildExtensions> {
        @Override
        public SmithyBuildExtensions validate(BuildFile source, Node node, Consumer<ValidationEvent> eventConsumer) {
            try {
                var config = NODE_MAPPER.deserialize(node, SmithyBuildExtensions.class);
                config.mergeMavenFromSmithyBuildConfig(SmithyBuildConfig.fromNode(node));
                return config;
            } catch (Exception e) {
                eventConsumer.accept(toEvent(e, source));
            }
            return null;
        }
    }

    private record SmithyProjectJsonValidator() implements BuildFileValidator<SmithyProjectJson> {
        @Override
        public SmithyProjectJson validate(BuildFile source, Node node, Consumer<ValidationEvent> eventConsumer) {
            try {
                return SmithyProjectJson.fromNode(node);
            } catch (Exception e) {
                eventConsumer.accept(toEvent(e, source));
            }
            return null;
        }
    }

    private static final NodeMapper NODE_MAPPER = new NodeMapper();
    private static final BuildFileType[] EXTS = {BuildFileType.SMITHY_BUILD_EXT_0, BuildFileType.SMITHY_BUILD_EXT_1};
    // Taken from smithy-cli ConfigurationUtils
    private static final Supplier<MavenRepository> CENTRAL = () -> MavenRepository.builder()
            .url("https://repo.maven.apache.org/maven2")
            .build();
    private static final Supplier<DependencyResolver> DEFAULT_RESOLVER_FACTORY = () ->
            new MavenDependencyResolver(EnvironmentVariable.SMITHY_MAVEN_CACHE.get());

    private final Path root;
    private final BuildFiles buildFiles;
    private final List<ValidationEvent> events = new ArrayList<>();
    private final Map<BuildFileType, Node> smithyNodes = new HashMap<>(BuildFileType.values().length);
    private final Supplier<DependencyResolver> dependencyResolverFactory;

    ProjectConfigLoader(Path root, BuildFiles buildFiles, Supplier<DependencyResolver> dependencyResolverFactory) {
        this.root = root;
        this.buildFiles = buildFiles;
        this.dependencyResolverFactory = dependencyResolverFactory;
    }

    ProjectConfigLoader(Path root, BuildFiles buildFiles) {
        this(root, buildFiles, DEFAULT_RESOLVER_FACTORY);
    }

    static List<ValidationEvent> validateBuildFiles(BuildFiles buildFiles) {
        List<ValidationEvent> events = new ArrayList<>();
        for (BuildFile buildFile : buildFiles) {
            BuildFileValidator<?> validator = switch (buildFile.type()) {
                case SMITHY_BUILD -> new SmithyBuildJsonValidator();
                case SMITHY_BUILD_EXT_0, SMITHY_BUILD_EXT_1 -> new SmithyBuildExtValidator();
                case SMITHY_PROJECT -> new SmithyProjectJsonValidator();
            };
            loadFile(buildFile, validator, events::add, (ignored) -> {
            });
        }
        return events;
    }

    ProjectConfig load() {
        SmithyBuildConfig smithyBuildConfig = loadFile(BuildFileType.SMITHY_BUILD, new SmithyBuildJsonValidator());

        SmithyBuildExtensions.Builder extBuilder = null;
        for (BuildFileType extType : EXTS) {
            SmithyBuildExtensions ext = loadFile(extType, new SmithyBuildExtValidator());
            if (ext != null) {
                if (extBuilder == null) {
                    extBuilder = SmithyBuildExtensions.builder();
                }
                extBuilder.merge(ext);
            }
        }

        SmithyBuildConfig merged = mergeSmithyBuildConfig(smithyBuildConfig, extBuilder);
        SmithyProjectJson smithyProjectJson = loadFile(BuildFileType.SMITHY_PROJECT, new SmithyProjectJsonValidator());

        ProjectConfig partial = createConfig(merged, smithyProjectJson);
        var resolveResult = resolve(partial);

        return new ProjectConfig(
                partial,
                resolveResult.modelPaths(),
                resolveResult.resolvedDependencies(),
                resolveResult.events()
        );
    }

    private <T> T loadFile(BuildFileType type, BuildFileValidator<T> validator) {
        var buildFile = buildFiles.getByType(type);
        if (buildFile != null) {
            return loadFile(buildFile, validator, events::add, (node) -> smithyNodes.put(type, node));
        }
        return null;
    }

    private static <T> T loadFile(
            BuildFile buildFile,
            BuildFileValidator<T> validator,
            Consumer<ValidationEvent> eventConsumer,
            Consumer<Node> nodeConsumer
    ) {
        var nodeResult = ToSmithyNode.toSmithyNode(buildFile);
        nodeResult.getValidationEvents().forEach(eventConsumer);
        var smithyNode = nodeResult.getResult().orElse(null);
        if (smithyNode != null) {
            nodeConsumer.accept(smithyNode);
            return validator.validate(buildFile, smithyNode, eventConsumer);
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
        SourceException asSourceException = null;
        if (e instanceof SourceException sourceException) {
            asSourceException = sourceException;
        } else if (e.getCause() instanceof SourceException sourceException) {
            asSourceException = sourceException;
        }

        if (asSourceException != null && !SourceLocation.NONE.equals(asSourceException.getSourceLocation())) {
            return ValidationEvent.fromSourceException(asSourceException);
        }

        return ValidationEvent.builder()
                .id("SmithyBuildConfig")
                .severity(Severity.ERROR)
                .message(e.getMessage())
                .sourceLocation(new SourceLocation(fallbackBuildFile.path(), 1, 1))
                .build();
    }

    private ProjectConfig createConfig(SmithyBuildConfig smithyBuildConfig, SmithyProjectJson smithyProjectJson) {
        // TODO: Make this more efficient with right-sized lists
        List<String> sources = new ArrayList<>();
        List<String> imports = new ArrayList<>();
        List<SmithyProjectJson.ProjectDependency> projectDependencies = new ArrayList<>();
        MavenConfig mavenConfig = null;

        if (smithyBuildConfig != null) {
            sources.addAll(smithyBuildConfig.getSources());
            imports.addAll(smithyBuildConfig.getImports());
            var mavenOpt = smithyBuildConfig.getMaven();
            if (mavenOpt.isPresent()) {
                mavenConfig = mavenOpt.get();
            }
        }

        if (smithyProjectJson != null) {
            sources.addAll(smithyProjectJson.sources());
            imports.addAll(smithyProjectJson.imports());
            projectDependencies.addAll(smithyProjectJson.dependencies());
        }

        return new ProjectConfig(
                sources,
                imports,
                projectDependencies,
                mavenConfig,
                buildFiles
        );
    }

    private ResolveResult resolve(ProjectConfig config) {
        Set<Path> mavenDependencies = resolveMaven(config.maven());
        Set<Path> projectDependencies = resolveProjectDependencies(config.projectDependencies());

        List<URL> resolvedDependencies = new ArrayList<>();
        try {
            for (var dep : mavenDependencies) {
                resolvedDependencies.add(dep.toUri().toURL());
            }
            for (var dep : projectDependencies) {
                resolvedDependencies.add(dep.toUri().toURL());
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        Set<Path> uniqueModelPaths = collectAllSmithyFilePaths(config.sources(), config.imports());
        List<Path> modelPaths = new ArrayList<>(uniqueModelPaths);

        return new ResolveResult(modelPaths, resolvedDependencies, events);
    }

    private Set<Path> resolveMaven(MavenConfig maven) {
        if (maven.getRepositories().isEmpty() && maven.getDependencies().isEmpty()) {
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
                throw new RuntimeException(
                        "Dependency was resolved (" + resolvedArtifact + "), but it was not found on disk at " + path);
            }
            dependencyPaths.add(path);
        }

        return dependencyPaths;
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

    private record ResolveResult(
            List<Path> modelPaths,
            List<URL> resolvedDependencies,
            List<ValidationEvent> events
    ) {}
}
