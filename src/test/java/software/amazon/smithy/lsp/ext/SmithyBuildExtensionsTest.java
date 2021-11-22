/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class SmithyBuildExtensionsTest {

    final Path DUMMY_PATH = Paths.get("smithy-build.json");

    @Test
    public void parsingSmithyBuildFromString() {
        try {
            String json = "{\"mavenRepositories\": [\"bla\", \"ta\"], \"mavenDependencies\": [\"a1\", \"a2\"]}";
            SmithyBuildExtensions loaded = SmithyBuildLoader.load(DUMMY_PATH, json);

            assertEquals(ImmutableList.of("bla", "ta"), loaded.getMavenRepositories());
            assertEquals(ImmutableList.of("a1", "a2"), loaded.getMavenDependencies());
        } catch (ValidationException e) {
            fail(e.toString());
        }
    }

    @Test
    public void partialParsing() {
        try {
            String noExtensions = "{}";
            SmithyBuildExtensions loadedNoExtensions = SmithyBuildLoader.load(DUMMY_PATH, noExtensions);
            assertEquals(ImmutableList.of(), loadedNoExtensions.getMavenDependencies());
            assertEquals(ImmutableList.of(), loadedNoExtensions.getMavenRepositories());

            String noConfiguration = "{\"imports\": [\".\"]}";
            SmithyBuildExtensions loadedNoConfiguration = SmithyBuildLoader.load(DUMMY_PATH, noConfiguration);
            assertEquals(ImmutableList.of(), loadedNoConfiguration.getMavenDependencies());
            assertEquals(ImmutableList.of(), loadedNoConfiguration.getMavenRepositories());

            String noRepositories = "{\"mavenDependencies\": [\"a1\", \"a2\"]}";
            SmithyBuildExtensions loadedNoRepositories = SmithyBuildLoader.load(DUMMY_PATH, noRepositories);
            assertEquals(ImmutableList.of("a1", "a2"), loadedNoRepositories.getMavenDependencies());
            assertEquals(ImmutableList.of(), loadedNoRepositories.getMavenRepositories());

            String noArtifacts = "{\"mavenRepositories\": [\"r1\", \"r2\"]}";
            SmithyBuildExtensions loadedNoArtifacts = SmithyBuildLoader.load(DUMMY_PATH, noArtifacts);
            assertEquals(ImmutableList.of(), loadedNoArtifacts.getMavenDependencies());
            assertEquals(ImmutableList.of("r1", "r2"), loadedNoArtifacts.getMavenRepositories());

        } catch (ValidationException e) {
            fail(e.toString());
        }
    }

    @Test
    public void merging() {
        SmithyBuildExtensions.Builder builder = SmithyBuildExtensions.builder();

        SmithyBuildExtensions other = SmithyBuildExtensions.builder().mavenDependencies(Arrays.asList("hello", "world"))
                .mavenRepositories(Arrays.asList("hi", "there")).build();

        SmithyBuildExtensions result = builder.mavenDependencies(Arrays.asList("d1", "d2"))
                .mavenRepositories(Arrays.asList("r1", "r2")).merge(other).build();

        assertEquals(ImmutableList.of("d1", "d2", "hello", "world"), result.getMavenDependencies());
        assertEquals(ImmutableList.of("r1", "r2", "hi", "there"), result.getMavenRepositories());
    }
}
