/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import software.amazon.smithy.build.SmithyBuild;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.EnvironmentVariable;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.MavenDependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.lsp.util.Result;

/**
 * Resolves all Maven dependencies and {@link ProjectDependency} for a project.
 *
 * <p>Resolving a {@link ProjectDependency} is as simple as getting its path
 * relative to the project root, but is done here in order to be loaded the
 * same way as Maven dependencies.
 * TODO: There are some things in here taken from smithy-cli. Should figure out
 *  if we can expose them through smithy-cli instead of duplicating them here to
 *  avoid drift.
 */
final class ProjectDependencyResolver {
    // Taken from smithy-cli ConfigurationUtils
    private static final Supplier<MavenRepository> CENTRAL = () -> MavenRepository.builder()
            .url("https://repo.maven.apache.org/maven2")
            .build();

    private ProjectDependencyResolver() {
    }

    static Result<List<Path>, Exception> resolveDependencies(Path root, ProjectConfig config) {
        return Result.ofFallible(() -> {
            List<Path> deps = ProjectDependencyResolver.create(config).resolve()
                    .stream()
                    .map(ResolvedArtifact::getPath)
                    .collect(Collectors.toCollection(ArrayList::new));
            config.dependencies().forEach((projectDependency) -> {
                // TODO: Not sure if this needs to check for existence
                Path path = root.resolve(projectDependency.path()).normalize();
                deps.add(path);
            });
            return deps;
        });
    }

    // Taken (roughly) from smithy-cli ClasspathAction::resolveDependencies
    private static DependencyResolver create(ProjectConfig config) {
        // TODO: Seeing what happens if we just don't use the file cache. When we do, at least for testing, the
        //  server writes a classpath.json to build/smithy/ which is used by all tests, messing everything up.
        DependencyResolver resolver = new MavenDependencyResolver(EnvironmentVariable.SMITHY_MAVEN_CACHE.get());

        Set<MavenRepository> configuredRepositories = getConfiguredMavenRepos(config);
        configuredRepositories.forEach(resolver::addRepository);

        // TODO: Support lock file ?
        config.maven().ifPresent(maven -> maven.getDependencies().forEach(resolver::addDependency));

        return resolver;
    }

    // TODO: If this cache file is necessary for the server's use cases, we may
    //  want to keep an in memory version of it so we don't write stuff to
    //  people's build dirs. Right now, we just don't use it at all.
    // Taken (roughly) from smithy-cli ClasspathAction::getCacheFile
    private static File getCacheFile(ProjectConfig config) {
        return getOutputDirectory(config).resolve("classpath.json").toFile();
    }

    // Taken from smithy-cli BuildOptions::resolveOutput
    private static Path getOutputDirectory(ProjectConfig config) {
        return config.outputDirectory()
                .map(Paths::get)
                .orElseGet(SmithyBuild::getDefaultOutputDirectory);
    }

    // Taken from smithy-cli ConfigurationUtils::getConfiguredMavenRepos
    private static Set<MavenRepository> getConfiguredMavenRepos(ProjectConfig config) {
        Set<MavenRepository> repositories = new LinkedHashSet<>();

        String envRepos = EnvironmentVariable.SMITHY_MAVEN_REPOS.get();
        if (envRepos != null) {
            for (String repo : envRepos.split("\\|")) {
                repositories.add(MavenRepository.builder().url(repo.trim()).build());
            }
        }

        Set<MavenRepository> configuredRepos = config.maven()
                .map(MavenConfig::getRepositories)
                .orElse(Collections.emptySet());

        if (!configuredRepos.isEmpty()) {
            repositories.addAll(configuredRepos);
        } else if (envRepos == null) {
            repositories.add(CENTRAL.get());
        }
        return repositories;
    }
}
