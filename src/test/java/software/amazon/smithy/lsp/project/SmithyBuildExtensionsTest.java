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

package software.amazon.smithy.lsp.project;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;

public class SmithyBuildExtensionsTest {
    @SuppressWarnings("deprecation")
    @Test
    public void merging() {
        SmithyBuildExtensions.Builder builder = SmithyBuildExtensions.builder();

        SmithyBuildExtensions other = SmithyBuildExtensions.builder().mavenDependencies(Arrays.asList("hello", "world"))
                .mavenRepositories(Arrays.asList("hi", "there")).imports(Arrays.asList("i3", "i4")).build();

        SmithyBuildExtensions result = builder.mavenDependencies(Arrays.asList("d1", "d2"))
                .mavenRepositories(Arrays.asList("r1", "r2")).imports(Arrays.asList("i1", "i2")).merge(other).build();

        MavenConfig mavenConfig = result.mavenConfig();
        assertThat(mavenConfig.getDependencies(), containsInAnyOrder("d1", "d2", "hello", "world"));
        List<String> urls = mavenConfig.getRepositories().stream()
                .map(MavenRepository::getUrl)
                .collect(Collectors.toList());
        assertThat(urls, containsInAnyOrder("r1", "r2", "hi", "there"));
        assertThat(result.imports(), containsInAnyOrder("i1", "i2", "i3", "i4"));
    }
}
