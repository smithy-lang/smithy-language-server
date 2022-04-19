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

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.Test;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;

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

    @Test
    public void definitionLocations() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models").toURI());
        Path modelMain = baseDir.resolve("main.smithy");
        Path modelTest = baseDir.resolve("test.smithy");
        List<Path> modelFiles = ImmutableList.of(modelMain, modelTest);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            Map<String, List<Location>> locations = hs.getProject().getLocations();

            correctLocation(locations, "SingleLine", 4, 0, 4, 23);
            correctLocation(locations, "MultiLine", 6, 8,10, 9);
            correctLocation(locations, "SingleTrait", 13, 0, 13, 18);
            correctLocation(locations, "MultiTrait", 17, 0,18, 14);
            correctLocation(locations, "MultiTraitAndLineComments", 32, 0,34, 1);
            correctLocation(locations,"MultiTraitAndDocComments", 43, 0,45, 1);
            correctLocation(locations, "OtherStructure", 4, 0, 8, 1);
        }
    }

    private void correctLocation(Map<String, List<Location>> locations, String shapeName, int startLine,
                                 int startColumn, int endLine, int endColumn) {
        Location location = locations.get(shapeName).get(0);
        Range range = new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
        assertEquals(range, location.getRange());
    }
}
