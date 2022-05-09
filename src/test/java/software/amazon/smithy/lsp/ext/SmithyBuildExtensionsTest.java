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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.Test;
import software.amazon.smithy.lsp.ext.model.MavenRepository;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;

public class SmithyBuildExtensionsTest {

    final Path DUMMY_PATH = Paths.get("smithy-build.json");

    @Test
    public void parsingSmithyBuildFromString() throws ValidationException {
        String mavenConfig = "{\"maven\": {\"dependencies\": [\"d1\", \"d2\"], \"repositories\":" +
                "[{\"url\": \"r1\"}, {\"url\": \"r2\"}]}}";
        SmithyBuildExtensions loadedMavenConfig = SmithyBuildLoader.load(DUMMY_PATH, mavenConfig);

        assertEquals(ImmutableList.of("d1", "d2"), loadedMavenConfig.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of("r1", "r2"), loadedMavenConfig.getMavenConfig().getRepositories()
                .stream().map(MavenRepository::getUrl).collect(Collectors.toList()));
    }

    @Test
    public void parsingSmithyBuildFromStringWithDeprecatedKeys() throws ValidationException {
        String json = "{\"mavenRepositories\": [\"bla\", \"ta\"], \"mavenDependencies\": [\"a1\", \"a2\"]}";
        SmithyBuildExtensions loaded = SmithyBuildLoader.load(DUMMY_PATH, json);

        assertEquals(ImmutableList.of("a1", "a2"), loaded.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of("bla", "ta"), loaded.getMavenConfig().getRepositories()
                .stream().map(MavenRepository::getUrl).collect(Collectors.toList()));
    }

    @Test
    public void partialParsing() throws ValidationException {
        String noExtensions = "{}";
        SmithyBuildExtensions loadedNoExtensions = SmithyBuildLoader.load(DUMMY_PATH, noExtensions);
        assertNotNull(loadedNoExtensions.getMavenConfig());
        assertEquals(ImmutableList.of(), loadedNoExtensions.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of(), loadedNoExtensions.getMavenConfig().getRepositories());

        String noConfiguration = "{\"imports\": [\".\"]}";
        SmithyBuildExtensions loadedNoConfiguration = SmithyBuildLoader.load(DUMMY_PATH, noConfiguration);
        assertNotNull(loadedNoConfiguration.getMavenConfig());
        assertEquals(ImmutableList.of(), loadedNoConfiguration.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of(), loadedNoConfiguration.getMavenConfig().getRepositories());

        String noRepositories = "{\"mavenDependencies\": [\"a1\", \"a2\"]}";
        SmithyBuildExtensions loadedNoRepositories = SmithyBuildLoader.load(DUMMY_PATH, noRepositories);
        assertNotNull(loadedNoRepositories.getMavenConfig());
        assertEquals(ImmutableList.of("a1", "a2"), loadedNoRepositories.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of(), loadedNoRepositories.getMavenConfig().getRepositories());

        String noArtifacts = "{\"mavenRepositories\": [\"r1\", \"r2\"]}";
        SmithyBuildExtensions loadedNoArtifacts = SmithyBuildLoader.load(DUMMY_PATH, noArtifacts);
        assertNotNull(loadedNoArtifacts.getMavenConfig());
        assertEquals(ImmutableList.of(), loadedNoArtifacts.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of("r1", "r2"), loadedNoArtifacts.getMavenConfig().getRepositories()
                .stream().map(MavenRepository::getUrl).collect(Collectors.toList()));
    }

    @Test
    public void preferMavenConfig() throws ValidationException {
        String conflictingConfig = "{\"maven\": {\"dependencies\": [\"d1\", \"d2\"], \"repositories\":" +
                "[{\"url\": \"r1\"}, {\"url\": \"r2\"}]}, \"mavenRepositories\": [\"m1\", \"m2\"]," +
                "\"mavenDependencies\": [\"a1\", \"a2\"]}";
        SmithyBuildExtensions loadedConflictingConfig = SmithyBuildLoader.load(DUMMY_PATH, conflictingConfig);
        assertEquals(ImmutableList.of("d1", "d2"), loadedConflictingConfig.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of("r1", "r2"), loadedConflictingConfig.getMavenConfig().getRepositories()
                .stream().map(MavenRepository::getUrl).collect(Collectors.toList()));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void merging() {
        SmithyBuildExtensions.Builder builder = SmithyBuildExtensions.builder();

        SmithyBuildExtensions other = SmithyBuildExtensions.builder().mavenDependencies(Arrays.asList("hello", "world"))
                .mavenRepositories(Arrays.asList("hi", "there")).imports(Arrays.asList("i3", "i4")).build();

        SmithyBuildExtensions result = builder.mavenDependencies(Arrays.asList("d1", "d2"))
                .mavenRepositories(Arrays.asList("r1", "r2")).imports(Arrays.asList("i1", "i2")).merge(other).build();

        assertEquals(ImmutableList.of("d1", "d2", "hello", "world"), result.getMavenConfig().getDependencies());
        assertEquals(ImmutableList.of("r1", "r2", "hi", "there"), result.getMavenConfig().getRepositories()
                .stream().map(MavenRepository::getUrl).collect(Collectors.toList()));
        assertEquals(ImmutableList.of("i1", "i2", "i3", "i4"), result.getImports());
    }
}
