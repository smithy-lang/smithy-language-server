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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.Test;
import software.amazon.smithy.build.model.MavenConfig;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.EnvironmentVariable;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.FileCacheResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.SinceTrait;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

public class SmithyProjectTest {

    @Test
    public void respectingImports() {
        List<String> imports = ListUtils.of("bla", "foo");
        Map<String, String> files = MapUtils.ofEntries(MapUtils.entry("test.smithy", "namespace testRoot"),
                MapUtils.entry("bar/test.smithy", "namespace testBar"),
                MapUtils.entry("foo/test.smithy", "namespace testFoo"),
                MapUtils.entry("bla/test.smithy", "namespace testBla"));

        SmithyBuildExtensions extensions = SmithyBuildExtensions.builder().imports(imports).build();

        try (Harness hs = Harness.builder().extensions(extensions).files(files).build()) {
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

        try (Harness hs = Harness.builder().files(files).build()) {
            List<File> expectedFiles = Files.walk(hs.getRoot().toPath())
                    .filter(f -> f.getFileName().toString().endsWith(Constants.SMITHY_EXTENSION)).map(Path::toFile)
                    .collect(Collectors.toList());

            List<File> smithyFiles = hs.getProject().getSmithyFiles();

            assertEquals(expectedFiles, smithyFiles);
        }
    }

    @Test
    public void defaultsToMavenCentral() throws Exception {
        SmithyBuildExtensions extensions = SmithyBuildExtensions.builder().build();
        MockDependencyResolver delegate = new MockDependencyResolver(ListUtils.of());
        File cache = File.createTempFile("classpath", ".json");
        DependencyResolver resolver = new FileCacheResolver(cache, System.currentTimeMillis(), delegate);
        try (Harness hs = Harness.builder().extensions(extensions).dependencyResolver(resolver).build()) {
            assertEquals(delegate.repositories.stream().findFirst().get().getUrl(), "https://repo.maven.apache.org/maven2");
        }
    }

    @Test
    public void cachesExternalJars() throws Exception {
        String repo1 = "https://repo.smithy.io";
        String repo2 = "https://repo.foo.com";
        System.setProperty(EnvironmentVariable.SMITHY_MAVEN_REPOS.toString(),
                String.join("|", repo1, repo2));
        String dependency = "com.foo:bar:1.0.0";
        MavenRepository configuredRepo = MavenRepository.builder()
                .url("https://repo.example.com")
                .httpCredentials("user:pw")
                .build();
        MavenConfig maven = MavenConfig.builder()
                .dependencies(ListUtils.of(dependency))
                .repositories(SetUtils.of(configuredRepo))
                .build();
        List<MavenRepository> expectedRepos = ListUtils.of(
                MavenRepository.builder().url(repo1).build(),
                MavenRepository.builder().url(repo2).build(),
                configuredRepo
        );
        SmithyBuildExtensions extensions = SmithyBuildExtensions.builder().maven(maven).build();
        File cache = File.createTempFile("classpath", ".json");
        File jar = File.createTempFile("foo", ".json");
        Files.write(jar.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        ResolvedArtifact artifact = ResolvedArtifact.fromCoordinates(jar.toPath(), "com.foo:bar:1.0.0");
        MockDependencyResolver delegate = new MockDependencyResolver(ListUtils.of(artifact));
        DependencyResolver resolver = new FileCacheResolver(cache, System.currentTimeMillis(), delegate);
        try (Harness hs = Harness.builder().extensions(extensions).dependencyResolver(resolver).build()) {
            assertTrue(delegate.repositories.containsAll(expectedRepos));
            assertEquals(expectedRepos.size(), delegate.repositories.size());
            assertEquals(dependency, delegate.coordinates.get(0));
            assertThat(IoUtils.readUtf8File(cache.toPath()), containsString(dependency));
        }
    }

    @Test
    public void ableToLoadWithUnknownTrait() throws Exception {
        Path modelFile = Paths.get(getClass().getResource("models/unknown-trait.smithy").toURI());
        try (Harness hs = Harness.builder().paths(modelFile).build()) {
            ValidatedResult<Model> modelValidatedResult = hs.getProject().getModel();
            assertFalse(modelValidatedResult.isBroken());
        }
    }

    @Test
    public void ignoresUnmodeledApplyStatements() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        Path main = baseDir.resolve("apply.smithy");
        Path imports = baseDir.resolve("apply-imports.smithy");

        try (Harness hs = Harness.builder().paths(main, imports).build()) {
            Map<ShapeId, Location> locationMap = hs.getProject().getLocations();

            // Structure shape unchanged by apply
            correctLocation(locationMap, "com.main#SomeOpInput", 12, 0, 15, 1);

            // Member is unchanged by apply
            correctLocation(locationMap, "com.main#SomeOpInput$body", 14, 4, 14, 16);

            // The mixed in member should have the source location from the mixin.
            correctLocation(locationMap, "com.main#SomeOpInput$isTest", 8, 4, 8, 19);

            // Structure shape unchanged by apply
            correctLocation(locationMap, "com.main#ArbitraryStructure", 25, 0, 27, 1);

            // Member is unchanged by apply
            correctLocation(locationMap, "com.main#ArbitraryStructure$member", 26, 4, 26, 18);

            // Mixed-in member in another namespace unchanged by apply
            correctLocation(locationMap, "com.imports#HasIsTestParam$isTest", 8, 4, 8, 19);

            // Structure in another namespace unchanged by apply
            correctLocation(locationMap, "com.imports#HasIsTestParam", 7, 0, 9, 1);
        }
    }

    // https://github.com/awslabs/smithy-language-server/issues/100
    @Test
    public void allowsEmptyStructsWithMixins() {
        String fileText = "$version: \"2\"\n" +
                "\n" +
                "namespace demo\n" +
                "\n" +
                "operation MyOp {\n" +
                "    output: MyOpOutput\n" +
                "}\n" +
                "\n" +
                "@output\n" +
                "structure MyOpOutput {}\n";

        Map<String, String> files = MapUtils.of("main.smithy", fileText);

        try (Harness hs = Harness.builder().files(files).build()) {
            assertNotNull(hs.getProject());
            Map<ShapeId, Location> locationMap = hs.getProject().getLocations();

            correctLocation(locationMap, "demo#MyOpOutput", 9, 0, 9, 23);
        }
    }

    // https://github.com/awslabs/smithy-language-server/issues/110
    // Note: This test is flaky, it may succeed even if the code being tested is incorrect.
    @Test
    public void handlesSameOperationNameBetweenNamespaces() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/operation-name-conflict").toURI());
        Path modelA = baseDir.resolve("a.smithy");
        Path modelB = baseDir.resolve("b.smithy");

        try (Harness hs = Harness.builder().paths(modelA, modelB).build()) {
            Map<ShapeId, Location> locationMap = hs.getProject().getLocations();

            correctLocation(locationMap, "a#HelloWorld", 4, 0, 13, 1);
            correctLocation(locationMap, "b#HelloWorld", 6, 0, 15, 1);
        }
    }

    @Test
    public void definitionLocationsV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        Path modelMain = baseDir.resolve("main.smithy");
        Path modelTest = baseDir.resolve("test.smithy");

        try (Harness hs = Harness.builder().paths(modelMain, modelTest).build()) {
            Map<ShapeId, Location> locationMap = hs.getProject().getLocations();

            correctLocation(locationMap, "com.foo#SingleLine", 4, 0, 4, 23);
            correctLocation(locationMap, "com.foo#MultiLine", 6, 8,13, 9);
            correctLocation(locationMap, "com.foo#SingleTrait", 16, 4, 16, 22);
            correctLocation(locationMap, "com.foo#MultiTrait", 20, 0,21, 14);
            correctLocation(locationMap, "com.foo#MultiTraitAndLineComments", 35, 0,37, 1);
            correctLocation(locationMap,"com.foo#MultiTraitAndDocComments", 46, 0,48, 1);
            correctLocation(locationMap, "com.example#OtherStructure", 7, 0, 11, 1);
        }
    }

    @Test
    public void definitionLocationsV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        Path modelMain = baseDir.resolve("main.smithy");
        Path modelTest = baseDir.resolve("test.smithy");

        try (Harness hs = Harness.builder().paths(modelMain, modelTest).build()) {
            Map<ShapeId, Location> locationMap = hs.getProject().getLocations();

            correctLocation(locationMap, "com.foo#SingleLine", 6, 0, 6, 23);
            correctLocation(locationMap, "com.foo#MultiLine", 8, 8,15, 9);
            correctLocation(locationMap, "com.foo#SingleTrait", 18, 4, 18, 22);
            correctLocation(locationMap, "com.foo#MultiTrait", 22, 0,23, 14);
            correctLocation(locationMap, "com.foo#MultiTraitAndLineComments", 37, 0,39, 1);
            correctLocation(locationMap, "com.foo#MultiTraitAndDocComments", 48, 0, 50, 1);
            correctLocation(locationMap, "com.example#OtherStructure", 8, 0, 13, 1);
            correctLocation(locationMap, "com.foo#StructWithDefaultSugar", 97, 0, 99, 1);
            correctLocation(locationMap, "com.foo#MyInlineOperation", 101, 0, 109, 1);
            correctLocation(locationMap, "com.foo#MyInlineOperationFooInput", 102, 13, 105, 5);
            correctLocation(locationMap, "com.foo#MyInlineOperationBarOutput", 106, 14, 108, 5);
            correctLocation(locationMap, "com.foo#UserIds", 112, 0, 118, 1);
            correctLocation(locationMap, "com.foo#UserIds$email", 114, 4, 114, 17);
            correctLocation(locationMap, "com.foo#UserIds$id", 117, 4, 117, 14);
            correctLocation(locationMap, "com.foo#UserDetails", 121, 0, 123, 1);
            correctLocation(locationMap, "com.foo#UserDetails$status", 122, 4, 122, 18);
            correctLocation(locationMap, "com.foo#GetUser", 125, 0, 132, 1);
            correctLocation(locationMap, "com.foo#GetUserFooInput", 126, 13, 128, 5);
            correctLocation(locationMap, "com.foo#GetUserBarOutput", 129, 14, 131, 5);
            correctLocation(locationMap, "com.foo#ElidedUserInfo", 134, 0, 140, 1);
            correctLocation(locationMap, "com.foo#ElidedUserInfo$email", 136, 4, 136, 10);
            correctLocation(locationMap, "com.foo#ElidedUserInfo$status", 139, 4, 139, 11);
            correctLocation(locationMap, "com.foo#ElidedGetUser", 142, 0, 155, 1);
            correctLocation(locationMap, "com.foo#ElidedGetUserFooInput", 143, 13, 148, 5);
            correctLocation(locationMap, "com.foo#ElidedGetUserFooInput$id", 146, 7, 146, 10);
            correctLocation(locationMap, "com.foo#ElidedGetUserFooInput$optional", 147, 7, 147, 23);
            correctLocation(locationMap, "com.foo#ElidedGetUserBarOutput", 149, 14, 154, 5);
            correctLocation(locationMap, "com.foo#ElidedGetUserBarOutput$status", 151, 8, 151, 15);
            correctLocation(locationMap, "com.foo#ElidedGetUserBarOutput$description", 153, 8, 153, 27);
            correctLocation(locationMap, "com.foo#Suit", 157, 0, 162, 1);
            correctLocation(locationMap, "com.foo#Suit$CLUB", 159, 4, 159, 17);
            correctLocation(locationMap, "com.foo#Suit$SPADE", 161, 4, 161, 19);

            correctLocation(locationMap, "com.foo#MyInlineOperationReversed", 164, 0, 171, 1);
            correctLocation(locationMap, "com.foo#MyInlineOperationReversedFooInput", 168, 13, 170, 5);
            correctLocation(locationMap, "com.foo#MyInlineOperationReversedBarOutput", 165, 14, 167, 5);

            correctLocation(locationMap, "com.foo#FalseInlined", 175, 0, 178, 1);
            correctLocation(locationMap, "com.foo#FalseInlinedFooInput", 180, 0, 182, 1);
            correctLocation(locationMap, "com.foo#FalseInlinedBarOutput", 184, 0, 186, 1);

            correctLocation(locationMap, "com.foo#FalseInlinedReversed", 188, 0, 191, 1);
            correctLocation(locationMap, "com.foo#FalseInlinedReversedFooInput", 193, 0, 195, 1);
            correctLocation(locationMap, "com.foo#FalseInlinedReversedBarOutput", 197, 0, 199, 1);

            // Elided members from source mixin structure.
            correctLocation(locationMap, "com.foo#ElidedUserInfo$id", 117, 4, 117, 14);
            correctLocation(locationMap, "com.foo#ElidedGetUserFooInput$email", 114, 4, 114, 17);
            correctLocation(locationMap, "com.foo#ElidedGetUserFooInput$status",  122, 4, 122, 18);
            correctLocation(locationMap, "com.foo#ElidedGetUserBarOutput$email",  114, 4, 114, 17);
            correctLocation(locationMap, "com.foo#ElidedGetUserBarOutput$id", 117, 4, 117, 14);
        }
    }

    @Test
    public void definitionLocationsEmptySourceLocationsOnTraitV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        Path modelMain = baseDir.resolve("empty-source-location-trait.smithy");

        StringShape stringShapeBar = StringShape.builder()
                .id("ns.foo#Bar")
                .source(new SourceLocation(modelMain.toString(), 5, 1))
                .build();

        StringShape stringShapeBaz = StringShape.builder()
                .id("ns.foo#Baz")
                .addTrait(new DocumentationTrait("docs", SourceLocation.NONE))
                .addTrait(new SinceTrait("2022-05-12", new SourceLocation(modelMain.toString(), 7, 1)))
                .source(new SourceLocation(modelMain.toString(), 8, 1))
                .build();

        Model unvalidatedModel = Model.builder()
                .addShape(stringShapeBar)
                .addShape(stringShapeBaz)
                .build();
        ValidatedResult<Model> model = Model.assembler().addModel(unvalidatedModel).assemble();
        Map<ShapeId, Location> locationMap = SmithyProject.collectLocations(model);

        correctLocation(locationMap, "ns.foo#Bar", 4, 0, 4, 10);
        correctLocation(locationMap, "ns.foo#Baz", 7, 0, 7, 10);
    }

    @Test
    public void definitionLocationsEmptySourceLocationsOnTraitV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        Path modelMain = baseDir.resolve("empty-source-location-trait.smithy");

        StringShape stringShapeBar = StringShape.builder()
                .id("ns.foo#Bar")
                .source(new SourceLocation(modelMain.toString(), 5, 1))
                .build();

        StringShape stringShapeBaz = StringShape.builder()
                .id("ns.foo#Baz")
                .addTrait(new DocumentationTrait("docs", SourceLocation.NONE))
                .addTrait(new SinceTrait("2022-05-12", new SourceLocation(modelMain.toString(), 7, 1)))
                .source(new SourceLocation(modelMain.toString(), 8, 1))
                .build();

        Model unvalidatedModel = Model.builder()
                .addShape(stringShapeBar)
                .addShape(stringShapeBaz)
                .build();
        ValidatedResult<Model> model = Model.assembler().addModel(unvalidatedModel).assemble();
        Map<ShapeId, Location> locationMap = SmithyProject.collectLocations(model);

        correctLocation(locationMap, "ns.foo#Bar", 4, 0, 4, 10);
        correctLocation(locationMap, "ns.foo#Baz", 7, 0, 7, 10);
    }

    @Test
    public void shapeIdFromLocationV1() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        Path modelMain = baseDir.resolve("main.smithy");
        Path modelTest = baseDir.resolve("test.smithy");

        try (Harness hs = Harness.builder().paths(modelMain, modelTest).build()) {
            SmithyProject project = hs.getProject();
            URI uri = hs.file("main.smithy").toURI();
            URI nonExistentUri = URI.create("non-existent-model-file.smithy");
            URI testUri = hs.file("test.smithy").toURI();

            assertFalse(project.getShapeIdFromLocation(nonExistentUri, new Position(0, 0)).isPresent());
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
                    new Position(7, 15)).get());
        }
    }

    @Test
    public void shapeIdFromLocationV2() throws Exception {
        Path baseDir = Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        Path modelMain = baseDir.resolve("main.smithy");
        Path modelTest = baseDir.resolve("test.smithy");

        try (Harness hs = Harness.builder().paths(modelMain, modelTest).build()) {
            SmithyProject project = hs.getProject();
            URI uri = hs.file("main.smithy").toURI();
            URI nonExistentUri = URI.create("non-existent-model-file.smithy");
            URI testUri = hs.file("test.smithy").toURI();

            assertFalse(project.getShapeIdFromLocation(nonExistentUri, new Position(0, 0)).isPresent());
            assertFalse(project.getShapeIdFromLocation(uri, new Position(0, 0)).isPresent());
            // Position on shape start line, but before char start
            assertFalse(project.getShapeIdFromLocation(uri, new Position(19, 0)).isPresent());
            // Position on shape end line, but after char end
            assertFalse(project.getShapeIdFromLocation(uri, new Position(16, 10)).isPresent());
            // Position on shape start line
            assertEquals(ShapeId.from("com.foo#SingleLine"), project.getShapeIdFromLocation(uri,
                    new Position(6, 10)).get());
            // Position on multi-line shape start line
            assertEquals(ShapeId.from("com.foo#MultiLine"), project.getShapeIdFromLocation(uri,
                    new Position(8, 8)).get());
            // Position on multi-line shape end line
            assertEquals(ShapeId.from("com.foo#MultiLine"), project.getShapeIdFromLocation(uri,
                    new Position(15, 6)).get());
            // Member positions
            assertEquals(ShapeId.from("com.foo#MultiLine$a"), project.getShapeIdFromLocation(uri,
                    new Position(9,14)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$b"), project.getShapeIdFromLocation(uri,
                    new Position(12,14)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$c"), project.getShapeIdFromLocation(uri,
                    new Position(14,14)).get());
            // Member positions on target
            assertEquals(ShapeId.from("com.foo#MultiLine$a"), project.getShapeIdFromLocation(uri,
                    new Position(9,18)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$b"), project.getShapeIdFromLocation(uri,
                    new Position(12,18)).get());
            assertEquals(ShapeId.from("com.foo#MultiLine$c"), project.getShapeIdFromLocation(uri,
                    new Position(14,18)).get());
            assertEquals(ShapeId.from("com.foo#GetUser"), project.getShapeIdFromLocation(uri,
                    new Position(125,13)).get());
            assertEquals(ShapeId.from("com.foo#GetUserFooInput$optional"), project.getShapeIdFromLocation(uri,
                    new Position(127,14)).get());
            assertEquals(ShapeId.from("com.foo#GetUserBarOutput"), project.getShapeIdFromLocation(uri,
                    new Position(129,19)).get());
            assertEquals(ShapeId.from("com.foo#GetUserBarOutput$description"), project.getShapeIdFromLocation(uri,
                    new Position(130,12)).get());
            assertEquals(ShapeId.from("com.foo#ElidedUserInfo"), project.getShapeIdFromLocation(uri,
                    new Position(134,17)).get());
            assertEquals(ShapeId.from("com.foo#ElidedUserInfo$email"), project.getShapeIdFromLocation(uri,
                    new Position(136,8)).get());
            assertEquals(ShapeId.from("com.foo#ElidedUserInfo$status"), project.getShapeIdFromLocation(uri,
                    new Position(139,9)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUser"), project.getShapeIdFromLocation(uri,
                    new Position(142,18)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUserFooInput"), project.getShapeIdFromLocation(uri,
                    new Position(144,18)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUserFooInput$id"), project.getShapeIdFromLocation(uri,
                    new Position(146,10)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUserFooInput$optional"), project.getShapeIdFromLocation(uri,
                    new Position(147,13)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUserBarOutput"), project.getShapeIdFromLocation(uri,
                    new Position(149,16)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUserBarOutput$status"), project.getShapeIdFromLocation(uri,
                    new Position(151,12)).get());
            assertEquals(ShapeId.from("com.foo#ElidedGetUserBarOutput$description"), project.getShapeIdFromLocation(uri,
                    new Position(153,18)).get());
            assertEquals(ShapeId.from("com.foo#Suit"), project.getShapeIdFromLocation(uri,
                    new Position(157,8)).get());
            assertEquals(ShapeId.from("com.foo#Suit$DIAMOND"), project.getShapeIdFromLocation(uri,
                    new Position(158,8)).get());
            assertEquals(ShapeId.from("com.foo#Suit$HEART"), project.getShapeIdFromLocation(uri,
                    new Position(160,8)).get());
            assertEquals(ShapeId.from("com.example#OtherStructure"), project.getShapeIdFromLocation(testUri,
                    new Position(8, 15)).get());
        }
    }

    @Test
    public void loadsProjectInDirectory() {
        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        String modelContents = "$version: \"2\"\nnamespace com.foo\nstructure Foo {}\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);
        SmithyProject project = SmithyProject.forDirectory(root);

        assertFalse(project.isBroken());
        assertFalse(project.getModel().isBroken());
        assertEquals(root, project.getRoot());
        assertEquals(1, project.getSmithyFiles().size());
        assertTrue(project.getSmithyFiles().contains(modelFile));
        assertTrue(project.getModelFiles().containsKey(modelFile.toURI()));
        assertEquals(modelContents, project.getModelFiles().get(modelFile.toURI()));
        assertEquals(2, project.getModelFiles().size());
    }

    @Test
    public void reloadsProject() {
        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        String modelContents = "$version: \"2\"\nnamespace com.foo\nstructure Foo {}\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);
        SmithyProject project = SmithyProject.forDirectory(root);
        SmithyProject reloaded = project.reload();

        assertFalse(reloaded.isBroken());
        assertFalse(reloaded.getModel().isBroken());
        assertEquals(root, reloaded.getRoot());
        assertEquals(1, reloaded.getSmithyFiles().size());
        assertTrue(reloaded.getSmithyFiles().contains(modelFile));
        assertTrue(reloaded.getModelFiles().containsKey(modelFile.toURI()));
        assertEquals(modelContents, reloaded.getModelFiles().get(modelFile.toURI()));
        assertEquals(2, reloaded.getModelFiles().size());
    }

    @Test
    public void reloadsProjectWithChangesToFile() {
        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        String modelContents = "$version: \"2\"\nnamespace com.foo\nstructure Foo {}\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);
        SmithyProject project = SmithyProject.forDirectory(root);

        String updatedModelContents = modelContents + "structure Bar {}\n";
        SmithyProject reloaded = project.reloadWithChanges(modelFile.toURI(), updatedModelContents);

        assertFalse(reloaded.isBroken());
        assertFalse(reloaded.getModel().isBroken());
        assertEquals(root, reloaded.getRoot());
        assertEquals(1, reloaded.getSmithyFiles().size());
        assertTrue(reloaded.getSmithyFiles().contains(modelFile));
        assertTrue(reloaded.getModelFiles().containsKey(modelFile.toURI()));
        assertEquals(updatedModelContents, reloaded.getModelFiles().get(modelFile.toURI()));
        assertEquals(2, reloaded.getModelFiles().size());
    }

    @Test
    public void reloadsProjectWithNewFile() {
        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        String modelContents = "$version: \"2\"\nnamespace com.foo\nstructure Foo {}\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);
        SmithyProject project = SmithyProject.forDirectory(root);

        String newModelFilename = "other.smithy";
        String newModelContents = "$version: \"2\"\nnamespace com.foo\nstructure Bar {}\n";
        File newModelFile = createModelFileInModelsDir(root, newModelFilename, newModelContents);
        SmithyProject reloaded = project.reloadWithChanges(newModelFile.toURI(), newModelContents);

        assertFalse(reloaded.isBroken());
        assertFalse(reloaded.getModel().isBroken());
        assertEquals(root, reloaded.getRoot());
        assertEquals(2, reloaded.getSmithyFiles().size());
        assertTrue(reloaded.getSmithyFiles().contains(modelFile));
        assertTrue(reloaded.getSmithyFiles().contains(newModelFile));
        assertTrue(reloaded.getModelFiles().containsKey(modelFile.toURI()));
        assertTrue(reloaded.getModelFiles().containsKey(newModelFile.toURI()));
        assertEquals(modelContents, reloaded.getModelFiles().get(modelFile.toURI()));
        assertEquals(newModelContents, reloaded.getModelFiles().get(newModelFile.toURI()));
        assertEquals(3, reloaded.getModelFiles().size());
    }

    @Test
    public void loadingProjectWithBrokenModel() {
        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        // Model has unknown shape
        String modelContents = "$version: \"2\"\nnamespace com.foo\nstructure Foo { bar: Bar }\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);
        SmithyProject project = SmithyProject.forDirectory(root);

        assertFalse(project.isBroken());
        assertTrue(project.getModel().isBroken());
        assertEquals(project.getRoot(), root);
        assertEquals(1, project.getSmithyFiles().size());
        assertTrue(project.getSmithyFiles().contains(modelFile));
        assertTrue(project.getModelFiles().containsKey(modelFile.toURI()));
        assertEquals(modelContents, project.getModelFiles().get(modelFile.toURI()));
        assertEquals(2, project.getModelFiles().size());
    }

    @Test
    public void reloadingProjectWithBrokenModelWithFixes() {
        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        // Model has unknown shape
        String modelContents = "$version: \"2\"\nnamespace com.foo\nstructure Foo { bar: Bar }\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);
        SmithyProject project = SmithyProject.forDirectory(root);

        assertTrue(project.getModel().isBroken());

        String updatedModelContents = modelContents + "structure Bar {}\n";
        SmithyProject reloaded = project.reloadWithChanges(modelFile.toURI(), updatedModelContents);

        assertFalse(reloaded.isBroken());
        assertFalse(reloaded.getModel().isBroken());
        assertEquals(root, reloaded.getRoot());
        assertEquals(1, reloaded.getSmithyFiles().size());
        assertTrue(reloaded.getSmithyFiles().contains(modelFile));
        assertTrue(reloaded.getModelFiles().containsKey(modelFile.toURI()));
        assertEquals(updatedModelContents, reloaded.getModelFiles().get(modelFile.toURI()));
        assertEquals(2, project.getModelFiles().size());
    }

    @Test
    public void loadingProjectWithBrokenConfig() {
        File root = getTestProjectRoot();
        String invalidSmithyBuildContents = "{";
        File smithyBuildFile = Paths.get(root.getAbsolutePath(), "smithy-build.json").toFile();
        writeToFile(smithyBuildFile, invalidSmithyBuildContents);
        SmithyProject project = SmithyProject.forDirectory(root);

        assertTrue(project.isBroken());
        assertEquals(1, project.getErrors().size());
        assertTrue(project.getErrors().get(0).contains("Error parsing JSON"));
    }

    @Test
    public void loadingProjectWithConfig() throws Exception {
        Path jarImportModelPath = Paths.get(getClass().getResource("models/jars/smithy-test-traits.jar").toURI());
        String coordinates = "com.example:smithy-test-traits:0.0.1";
        DependencyResolver dependencyResolver = new MockDependencyResolver(
                ResolvedArtifact.fromCoordinates(jarImportModelPath, coordinates)
        );
        MavenConfig mavenConfig = MavenConfig.builder()
                .dependencies(ListUtils.of(coordinates))
                .build();
        SmithyBuildExtensions buildExtensions = SmithyBuildExtensions.builder()
                .maven(mavenConfig)
                .build();

        File root = getTestProjectRoot();
        String modelFilename = "main.smithy";
        String modelContents = "$version: \"2\"\nnamespace com.foo\nuse smithy.test#test\n@test()service Weather {}\n";
        File modelFile = createModelFileInModelsDir(root, modelFilename, modelContents);

        SmithyProject project = SmithyProject.load(buildExtensions, root, dependencyResolver);

        assertFalse(project.isBroken());
        assertFalse(project.getModel().isBroken());
        assertEquals(root, project.getRoot());
        assertEquals(1, project.getSmithyFiles().size());
        assertTrue(project.getSmithyFiles().contains(modelFile));
        assertEquals(1, project.getExternalJars().size());
        assertTrue(project.getExternalJars().contains(jarImportModelPath.toFile()));
        assertEquals(3, project.getModelFiles().size());
    }

    private static File getTestProjectRoot() {
        try {
            File root = Files.createTempDirectory("test").toFile();
            root.deleteOnExit();
            return root;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static File createModelFileInModelsDir(File root, String filename, String contents) {
        File modelRoot = Paths.get(root.getAbsolutePath(), "model").toFile();
        if (!modelRoot.exists()) {
            modelRoot.mkdirs();
        }
        File modelFile = Paths.get(modelRoot.getAbsolutePath(), filename).toFile();
        writeToFile(modelFile, contents);
        return modelFile;
    }

    private static void writeToFile(File file, String contents) {
        try {
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(contents);
                fw.flush();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void correctLocation(Map<ShapeId, Location> locationMap, String shapeId, int startLine,
                                 int startColumn, int endLine, int endColumn) {
        Location location = locationMap.get(ShapeId.from(shapeId));
        Range range = new Range(new Position(startLine, startColumn), new Position(endLine, endColumn));
        assertEquals(range, location.getRange());
    }
}
