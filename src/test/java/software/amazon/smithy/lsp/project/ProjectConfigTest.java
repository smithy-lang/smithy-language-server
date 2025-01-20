/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithMessage;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithSourceLocation;
import static software.amazon.smithy.lsp.project.ProjectTest.toPath;
import static software.amazon.smithy.lsp.protocol.LspAdapter.toSourceLocation;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.DependencyResolverException;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.document.Document;

public class ProjectConfigTest {
    @Test
    public void loadsConfigWithEnvVariable() {
        System.setProperty("FOO", "bar");
        Path root = toPath(getClass().getResource("env-config"));
        ProjectConfig config = load(root).config();

        assertThat(config.maven().getRepositories(), hasSize(1));
        MavenRepository repository = config.maven().getRepositories().stream().findFirst().get();
        assertThat(repository.getUrl(), containsString("example.com/maven/my_repo"));
        assertThat(repository.getHttpCredentials().isPresent(), is(true));
        assertThat(repository.getHttpCredentials().get(), containsString("my_user:bar"));
    }

    @Test
    public void loadsLegacyConfig() {
        Path root = toPath(getClass().getResource("legacy-config"));
        ProjectConfig config = load(root).config();

        assertThat(config.maven().getDependencies(), containsInAnyOrder("baz"));
        assertThat(config.maven().getRepositories().stream()
                        .map(MavenRepository::getUrl)
                        .collect(Collectors.toList()), containsInAnyOrder("foo", "bar"));
    }

    @Test
    public void prefersNonLegacyConfig() {
        Path root = toPath(getClass().getResource("legacy-config-with-conflicts"));
        ProjectConfig config = load(root).config();

        assertThat(config.maven().getDependencies(), containsInAnyOrder("dep1", "dep2"));
        assertThat(config.maven().getRepositories().stream()
                .map(MavenRepository::getUrl)
                .collect(Collectors.toList()), containsInAnyOrder("url1", "url2"));
    }

    @Test
    public void mergesBuildExts() {
        Path root = toPath(getClass().getResource("build-exts"));
        ProjectConfig config = load(root).config();

        assertThat(config.imports(), containsInAnyOrder(containsString("main.smithy"), containsString("other.smithy")));
    }

    @Test
    public void validatesSmithyBuildJson() {
        var text = TextWithPositions.from("""
                {
                    "version" : %1, // Should be a string
                    "sources": ["foo"]
                }
                """);
        var eventPosition = text.positions()[0];
        Path root = Path.of("test");
        var buildFiles = createBuildFiles(root, BuildFileType.SMITHY_BUILD, text.text());
        var result = load(root, buildFiles);

        var buildFile = buildFiles.getByType(BuildFileType.SMITHY_BUILD);
        assertThat(buildFile, notNullValue());
        assertThat(result.events(), containsInAnyOrder(
                eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), eventPosition)))
        ));
        assertThat(result.config().sources(), empty());
    }

    @Test
    public void validatesSmithyProjectJson() {
        var text = TextWithPositions.from("""
                {
                    "sources": ["foo"],
                    "dependencies": [
                        %"foo" // Should be an object
                    ]
                }
                """);
        var eventPosition = text.positions()[0];
        Path root = Path.of("test");
        var buildFiles = createBuildFiles(root, BuildFileType.SMITHY_PROJECT, text.text());
        var result = load(root, buildFiles);

        var buildFile = buildFiles.getByType(BuildFileType.SMITHY_PROJECT);
        assertThat(buildFile, notNullValue());
        assertThat(result.events(), containsInAnyOrder(
                eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), eventPosition)))
        ));
        assertThat(result.config().sources(), empty());
    }

    @Test
    public void validatesMavenConfig() {
        // "httpCredentials" is invalid, but we don't get the source location in the exception
        var text = TextWithPositions.from("""
                %{
                    "version" : "1",
                    "sources": ["foo"],
                    "maven": {
                        "repositories": [
                            {
                                "url": "foo",
                                "httpCredentials": "bar"
                            }
                        ]
                    }
                }
                """);
        var eventPosition = text.positions()[0];
        Path root = Path.of("test");
        var buildFiles = createBuildFiles(root, BuildFileType.SMITHY_BUILD, text.text());
        var result = load(root, buildFiles);

        var buildFile = buildFiles.getByType(BuildFileType.SMITHY_BUILD);
        assertThat(buildFile, notNullValue());
        assertThat(result.events(), containsInAnyOrder(allOf(
                eventWithMessage(containsString("httpCredentials")),
                eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), eventPosition)))
        )));
        assertThat(result.config().sources(), empty());
    }

    @Test
    public void resolveValidatesFilesExist() {
        var text = TextWithPositions.from("""
                {
                    "sources": [%"foo"],
                    "imports": [%"bar"],
                    "dependencies": [
                        {
                            "name": "baz",
                            "path": %"baz"
                        }
                    ]
                }
                """);
        var notFoundSourcePosition = text.positions()[0];
        var notFoundImportPosition = text.positions()[1];
        var notFoundDepPosition = text.positions()[2];
        var root = Path.of("test");
        var buildFiles = createBuildFiles(root, BuildFileType.SMITHY_PROJECT, text.text());
        var result = load(root, buildFiles);

        var buildFile = buildFiles.getByType(BuildFileType.SMITHY_PROJECT);
        assertThat(buildFile, notNullValue());
        assertThat(result.events(), containsInAnyOrder(
                allOf(
                        eventWithId(equalTo("FileNotFound")),
                        eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), notFoundSourcePosition)))
                ),
                allOf(
                        eventWithId(equalTo("FileNotFound")),
                        eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), notFoundImportPosition)))
                ),
                allOf(
                        eventWithId(equalTo("FileNotFound")),
                        eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), notFoundDepPosition)))
                )
        ));
        assertThat(result.config().sources(), containsInAnyOrder(equalTo("foo")));
        assertThat(result.config().imports(), containsInAnyOrder(equalTo("bar")));
        assertThat(result.config().projectDependencies().getFirst().path(), equalTo("baz"));
    }

    @Test
    public void resolveValidatesMavenDependencies() {
        var text = TextWithPositions.from("""
                {
                    "version": "1",
                    %"maven": {
                        "dependencies": ["foo"],
                        "repositories": [
                            {
                                "url": "bar"
                            }
                        ]
                    }
                }
                """);
        var eventPosition = text.positions()[0];
        var root = Path.of("test");
        Supplier<DependencyResolver> resolverFactory = () -> new DependencyResolver() {
            @Override
            public void addRepository(MavenRepository mavenRepository) {
                throw new DependencyResolverException("repo " + mavenRepository.getUrl());
            }

            @Override
            public void addDependency(String s) {
                throw new DependencyResolverException("dep " + s);
            }

            @Override
            public List<ResolvedArtifact> resolve() {
                throw new DependencyResolverException("call resolve");
            }
        };
        var buildFiles = createBuildFiles(root, BuildFileType.SMITHY_BUILD, text.text());
        var result = new ProjectConfigLoader(root, buildFiles, resolverFactory).load();

        var buildFile = buildFiles.getByType(BuildFileType.SMITHY_BUILD);
        assertThat(buildFile, notNullValue());
        assertThat(result.events(), containsInAnyOrder(
                allOf(
                        eventWithId(equalTo("DependencyResolver")),
                        eventWithMessage(allOf(
                                containsString("repo bar"),
                                containsString("dep foo"),
                                containsString("call resolve")
                        )),
                        eventWithSourceLocation(equalTo(toSourceLocation(buildFile.path(), eventPosition)))
                )
        ));
    }

    private record NoOpResolver() implements DependencyResolver {
        @Override
        public void addRepository(MavenRepository mavenRepository) {
        }

        @Override
        public void addDependency(String s) {
        }

        @Override
        public List<ResolvedArtifact> resolve() {
            return List.of();
        }
    }

    private static BuildFiles createBuildFiles(Path root, BuildFileType type, String content) {
        var buildFile = BuildFile.create(root.resolve(type.filename()).toString(), Document.of(content), type);
        return BuildFiles.of(List.of(buildFile));
    }

    private static ProjectConfigLoader.Result load(Path root, BuildFiles buildFiles) {
        var loader = new ProjectConfigLoader(root, buildFiles, NoOpResolver::new);
        return loader.load();
    }

    private static ProjectConfigLoader.Result load(Path root) {
        var buildFiles = BuildFiles.load(root, new ServerState());
        var loader = new ProjectConfigLoader(root, buildFiles, NoOpResolver::new);
        return loader.load();
    }
}
