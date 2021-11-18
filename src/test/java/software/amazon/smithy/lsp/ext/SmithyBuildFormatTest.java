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

import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class SmithyBuildFormatTest {

    @Test
    public void parsingSmithyBuildFromString() {
        try {
            String json = "{\"extensions\": {\"repositories\": [\"bla\", \"ta\"], \"artifacts\": [\"a1\", \"a2\"]}}";
            SmithyBuildExtensions loaded = SmithyBuildExtensions.load(json);

            assertEquals(ImmutableList.of("bla", "ta"), loaded.getRepositories());
            assertEquals(ImmutableList.of("a1", "a2"), loaded.getArtifacts());
        } catch (ValidationException e) {
            fail(e.toString());
        }
    }

    @Test
    public void partialParsing() {
        try {
            String noExtensions = "{}";
            SmithyBuildExtensions loadedNoExtensions = SmithyBuildExtensions.load(noExtensions);
            assertEquals(ImmutableList.of(), loadedNoExtensions.getArtifacts());
            assertEquals(ImmutableList.of(), loadedNoExtensions.getRepositories());

            String noConfiguration = "{\"extensions\": {}}";
            SmithyBuildExtensions loadedNoConfiguration = SmithyBuildExtensions.load(noConfiguration);
            assertEquals(ImmutableList.of(), loadedNoConfiguration.getArtifacts());
            assertEquals(ImmutableList.of(), loadedNoConfiguration.getRepositories());

            String noRepositories = "{\"extensions\": {\"artifacts\": [\"a1\", \"a2\"]}}";
            SmithyBuildExtensions loadedNoRepositories = SmithyBuildExtensions.load(noRepositories);
            assertEquals(ImmutableList.of("a1", "a2"), loadedNoRepositories.getArtifacts());
            assertEquals(ImmutableList.of(), loadedNoRepositories.getRepositories());

            String noArtifacts = "{\"extensions\": {\"repositories\": [\"r1\", \"r2\"]}}";
            SmithyBuildExtensions loadedNoArtifacts = SmithyBuildExtensions.load(noArtifacts);
            assertEquals(ImmutableList.of(), loadedNoArtifacts.getArtifacts());
            assertEquals(ImmutableList.of("r1", "r2"), loadedNoArtifacts.getRepositories());

        } catch (ValidationException e) {
            fail(e.toString());
        }
    }
}
