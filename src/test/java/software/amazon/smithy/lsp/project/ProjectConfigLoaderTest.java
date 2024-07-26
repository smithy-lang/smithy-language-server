/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static software.amazon.smithy.lsp.project.ProjectTest.toPath;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.lsp.util.Result;

public class ProjectConfigLoaderTest {
    @Test
    public void loadsConfigWithEnvVariable() {
        System.setProperty("FOO", "bar");
        Path root = toPath(getClass().getResource("env-config"));
        Result<ProjectConfig, List<Exception>> result = ProjectConfigLoader.loadFromRoot(root);

        assertThat(result.isOk(), is(true));
        ProjectConfig config = result.unwrap();
        assertThat(config.maven().isPresent(), is(true));
        MavenConfig mavenConfig = config.maven().get();
        assertThat(mavenConfig.getRepositories(), hasSize(1));
        MavenRepository repository = mavenConfig.getRepositories().stream().findFirst().get();
        assertThat(repository.getUrl(), containsString("example.com/maven/my_repo"));
        assertThat(repository.getHttpCredentials().isPresent(), is(true));
        assertThat(repository.getHttpCredentials().get(), containsString("my_user:bar"));
    }

    @Test
    public void loadsLegacyConfig() {
        Path root = toPath(getClass().getResource("legacy-config"));
        Result<ProjectConfig, List<Exception>> result = ProjectConfigLoader.loadFromRoot(root);

        assertThat(result.isOk(), is(true));
        ProjectConfig config = result.unwrap();
        assertThat(config.maven().isPresent(), is(true));
        MavenConfig mavenConfig = config.maven().get();
        assertThat(mavenConfig.getDependencies(), containsInAnyOrder("baz"));
        assertThat(mavenConfig.getRepositories().stream()
                        .map(MavenRepository::getUrl)
                        .collect(Collectors.toList()), containsInAnyOrder("foo", "bar"));
    }

    @Test
    public void prefersNonLegacyConfig() {
        Path root = toPath(getClass().getResource("legacy-config-with-conflicts"));
        Result<ProjectConfig, List<Exception>> result = ProjectConfigLoader.loadFromRoot(root);

        assertThat(result.isOk(), is(true));
        ProjectConfig config = result.unwrap();
        assertThat(config.maven().isPresent(), is(true));
        MavenConfig mavenConfig = config.maven().get();
        assertThat(mavenConfig.getDependencies(), containsInAnyOrder("dep1", "dep2"));
        assertThat(mavenConfig.getRepositories().stream()
                .map(MavenRepository::getUrl)
                .collect(Collectors.toList()), containsInAnyOrder("url1", "url2"));
    }

    @Test
    public void mergesBuildExts() {
        Path root = toPath(getClass().getResource("build-exts"));
        Result<ProjectConfig, List<Exception>> result = ProjectConfigLoader.loadFromRoot(root);

        assertThat(result.isOk(), is(true));
        ProjectConfig config = result.unwrap();
        assertThat(config.imports(), containsInAnyOrder(containsString("main.smithy"), containsString("other.smithy")));
    }
}
