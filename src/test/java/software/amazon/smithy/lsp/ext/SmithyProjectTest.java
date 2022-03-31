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

import org.junit.Test;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class SmithyProjectTest {

    @Test
    public void respectingImports() throws Exception {
        List<String> imports = Arrays.asList("bla", "foo");
        Map<String, String> files = MapUtils.ofEntries(MapUtils.entry("test.smithy", "namespace testRoot"),
                MapUtils.entry("bar/test.smithy", "namespace testBar"),
                MapUtils.entry("foo/test.smithy", "namespace testFoo"),
                MapUtils.entry("bla/test.smithy", "namespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().imports(imports).build(), files)) {
            File inFoo = hs.file("foo/test.smithy");
            File inBla = hs.file("bla/test.smithy");

            List<File> smithyFiles = hs.getProject().getSmithyFiles();

            assertEquals(ListUtils.of(inBla, inFoo), smithyFiles);
        }

    }

    @Test
    public void respectingEmptyConfig() throws Exception {
        Map<String, String> files = MapUtils.ofEntries(MapUtils.entry("test.smithy", "namespace testRoot"),
                MapUtils.entry("bar/test.smithy", "namespace testBar"),
                MapUtils.entry("foo/test.smithy", "namespace testFoo"),
                MapUtils.entry("bla/test.smithy", "namespace testBla"));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {

            List<File> expectedFiles = Files.walk(hs.getRoot().toPath())
                    .filter(f -> f.getFileName().toString().endsWith(Constants.SMITHY_EXTENSION)).map(Path::toFile)
                    .collect(Collectors.toList());

            List<File> smithyFiles = hs.getProject().getSmithyFiles();

            assertEquals(expectedFiles, smithyFiles);
        }

    }

}
