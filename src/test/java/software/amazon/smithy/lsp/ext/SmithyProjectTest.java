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
import static org.junit.Assert.assertFalse;

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
import software.amazon.smithy.model.shapes.ShapeId;
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
            Map<ShapeId, Location> locationMap = hs.getProject().getLocations();

            correctLocation(locationMap, "com.foo#SingleLine", 4, 0, 4, 23);
            correctLocation(locationMap, "com.foo#MultiLine", 6, 8,13, 9);
            correctLocation(locationMap, "com.foo#SingleTrait", 16, 4, 16, 22);
            correctLocation(locationMap, "com.foo#MultiTrait", 20, 0,21, 14);
            correctLocation(locationMap, "com.foo#MultiTraitAndLineComments", 35, 0,37, 1);
            correctLocation(locationMap,"com.foo#MultiTraitAndDocComments", 46, 0,48, 1);
            correctLocation(locationMap, "com.example#OtherStructure", 4, 0, 8, 1);
        }
    }

    @Test
    public void shapeIdFromLocation() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models").toURI());
        Path modelMain = baseDir.resolve("main.smithy");
        Path modelTest = baseDir.resolve("test.smithy");
        List<Path> modelFiles = ImmutableList.of(modelMain, modelTest);

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), modelFiles)) {
            SmithyProject project = hs.getProject();
            String uri = "file:" + hs.file("main.smithy");
            String testUri = "file:" + hs.file("test.smithy");

            assertFalse(project.getShapeIdFromLocation("empty.smithy", new Position(0, 0)).isPresent());
            assertFalse(project.getShapeIdFromLocation(uri, new Position(0, 0)).isPresent());
            // Position on shape start line, but before char start
            assertFalse(project.getShapeIdFromLocation(uri, new Position(17, 0)).isPresent());
            // Position on shape end line, but after char end
            assertFalse(project.getShapeIdFromLocation(uri, new Position(14, 10)).isPresent());
            // Position on shape start line
            assertEquals(ShapeId.from("com.foo#SingleLine"), project.getShapeIdFromLocation(uri,
                    new Position(4, 10)).get());
            // Position on multi-line shape start line
            assertEquals(ShapeId.from("com.foo#MultiLine"), project.getShapeIdFromLocation(uri,
                    new Position(6, 8)).get());
            // Position on multi-line shape end line
            assertEquals(ShapeId.from("com.foo#MultiLine"), project.getShapeIdFromLocation(uri,
                    new Position(13, 6)).get());
            // Member positions
            assertEquals(ShapeId.from("com.foo#MultiLine$a"), project.getShapeIdFromLocation(uri,
                    new Position(7,14)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$b"), project.getShapeIdFromLocation(uri,
                    new Position(10,14)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$c"), project.getShapeIdFromLocation(uri,
                    new Position(12,14)).get());
            // Member positions on target
            assertEquals(ShapeId.from("com.foo#MultiLine$a"), project.getShapeIdFromLocation(uri,
                    new Position(7,18)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$b"), project.getShapeIdFromLocation(uri,
                    new Position(10,18)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$c"), project.getShapeIdFromLocation(uri,
                    new Position(12,18)).get());
            assertEquals(ShapeId.from("com.example#OtherStructure"), project.getShapeIdFromLocation(testUri,
                    new Position(4, 15)).get());
        }
    }

    private void correctLocation(Map<ShapeId, Location> locationMap, String shapeId, int startLine,
                                 int startColumn, int endLine, int endColumn) {
        Location location = locationMap.get(ShapeId.from(shapeId));
        Range range = new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
        assertEquals(range, location.getRange());
    }
}
