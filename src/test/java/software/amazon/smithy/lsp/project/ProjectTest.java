/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithMessage;
import static software.amazon.smithy.lsp.SmithyMatchers.hasShapeWithId;
import static software.amazon.smithy.lsp.UtilMatchers.anOptionalOf;
import static software.amazon.smithy.lsp.document.DocumentTest.string;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.protocol.RangeAdapter;
import software.amazon.smithy.lsp.protocol.UriAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.LengthTrait;
import software.amazon.smithy.model.traits.PatternTrait;
import software.amazon.smithy.model.traits.TagsTrait;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ProjectTest {
    @Test
    public void loadsFlatProject() {
        Path root = toPath(getClass().getResource("flat"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.getImports(), empty());
        assertThat(project.getDependencies(), empty());
        assertThat(project.getModelResult().isBroken(), is(false));
        assertThat(project.getModelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsProjectWithMavenDep() {
        Path root = toPath(getClass().getResource("maven-dep"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.getImports(), empty());
        assertThat(project.getDependencies(), hasSize(3));
        assertThat(project.getModelResult().isBroken(), is(false));
        assertThat(project.getModelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsProjectWithSubdir() {
        Path root = toPath(getClass().getResource("subdirs"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItems(
                root.resolve("model"),
                root.resolve("model2")));
        assertThat(project.getSmithyFiles().keySet(), hasItems(
                equalTo(root.resolve("model/main.smithy").toString()),
                equalTo(root.resolve("model/subdir/sub.smithy").toString()),
                equalTo(root.resolve("model2/subdir2/sub2.smithy").toString()),
                equalTo(root.resolve("model2/subdir2/subsubdir/subsub.smithy").toString())));
        assertThat(project.getModelResult().isBroken(), is(false));
        assertThat(project.getModelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
        assertThat(project.getModelResult().unwrap(), hasShapeWithId("com.foo#Bar"));
        assertThat(project.getModelResult().unwrap(), hasShapeWithId("com.foo#Baz"));
    }

    @Test
    public void loadsModelWithUnknownTrait() {
        Path root = toPath(getClass().getResource("unknown-trait"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.getModelResult().isBroken(), is(false)); // unknown traits don't break it

        List<String> eventIds = project.getModelResult().getValidationEvents().stream()
                .map(ValidationEvent::getId)
                .collect(Collectors.toList());
        assertThat(eventIds, hasItem(containsString("UnresolvedTrait")));
        assertThat(project.getModelResult().getResult().isPresent(), is(true));
        assertThat(project.getModelResult().getResult().get(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsWhenModelHasInvalidSyntax() {
        Path root = toPath(getClass().getResource("invalid-syntax"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.getModelResult().isBroken(), is(true));
        List<String> eventIds = project.getModelResult().getValidationEvents().stream()
                .map(ValidationEvent::getId)
                .collect(Collectors.toList());
        assertThat(eventIds, hasItem("Model"));

        assertThat(project.getSmithyFiles().keySet(), hasItem(containsString("main.smithy")));
        SmithyFile main = project.getSmithyFile(UriAdapter.toUri(root.resolve("main.smithy").toString()));
        assertThat(main, not(nullValue()));
        assertThat(main.getDocument(), not(nullValue()));
        assertThat(main.getNamespace(), string("com.foo"));
        assertThat(main.getImports(), empty());

        assertThat(main.getShapes(), hasSize(2));
        List<String> shapeIds = main.getShapes().stream()
                .map(Shape::toShapeId)
                .map(ShapeId::toString)
                .collect(Collectors.toList());
        assertThat(shapeIds, hasItems("com.foo#Foo", "com.foo#Foo$bar"));

        assertThat(main.getDocumentShapes(), hasSize(3));
        List<String> documentShapeNames = main.getDocumentShapes().stream()
                .map(documentShape -> documentShape.shapeName().toString())
                .collect(Collectors.toList());
        assertThat(documentShapeNames, hasItems("Foo", "bar", "String"));
    }

    @Test
    public void loadsProjectWithMultipleNamespaces() {
        Path root = toPath(getClass().getResource("multiple-namespaces"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getSources(), hasItem(root.resolve("model")));
        assertThat(project.getModelResult().getValidationEvents(), empty());
        assertThat(project.getSmithyFiles().keySet(), hasItems(containsString("a.smithy"), containsString("b.smithy")));

        SmithyFile a = project.getSmithyFile(UriAdapter.toUri(root.resolve("model/a.smithy").toString()));
        assertThat(a.getDocument(), not(nullValue()));
        assertThat(a.getNamespace(), string("a"));
        List<String> aShapeIds = a.getShapes().stream()
                .map(Shape::toShapeId)
                .map(ShapeId::toString)
                .collect(Collectors.toList());
        assertThat(aShapeIds, hasItems("a#Hello", "a#HelloInput", "a#HelloOutput"));
        List<String> aDocumentShapeNames = a.getDocumentShapes().stream()
                .map(documentShape -> documentShape.shapeName().toString())
                .collect(Collectors.toList());
        assertThat(aDocumentShapeNames, hasItems("Hello", "name", "String"));

        SmithyFile b = project.getSmithyFile(UriAdapter.toUri(root.resolve("model/b.smithy").toString()));
        assertThat(b.getDocument(), not(nullValue()));
        assertThat(b.getNamespace(), string("b"));
        List<String> bShapeIds = b.getShapes().stream()
                .map(Shape::toShapeId)
                .map(ShapeId::toString)
                .collect(Collectors.toList());
        assertThat(bShapeIds, hasItems("b#Hello", "b#HelloInput", "b#HelloOutput"));
        List<String> bDocumentShapeNames = b.getDocumentShapes().stream()
                .map(documentShape -> documentShape.shapeName().toString())
                .collect(Collectors.toList());
        assertThat(bDocumentShapeNames, hasItems("Hello", "name", "String"));
    }

    @Test
    public void loadsProjectWithExternalJars() {
        Path root = toPath(getClass().getResource("external-jars"));
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isOk(), is(true));
        Project project = result.unwrap();
        assertThat(project.getSources(), containsInAnyOrder(root.resolve("test-traits.smithy"), root.resolve("test-validators.smithy")));
        assertThat(project.getSmithyFiles().keySet(), hasItems(
                containsString("test-traits.smithy"),
                containsString("test-validators.smithy"),
                containsString("smithy-test-traits.jar!/META-INF/smithy/smithy.test.json"),
                containsString("alloy-core.jar!/META-INF/smithy/uuid.smithy")));

        assertThat(project.getModelResult().isBroken(), is(true));
        assertThat(project.getModelResult().getValidationEvents(Severity.ERROR), hasItem(eventWithMessage(containsString("Proto index 1"))));

        assertThat(project.getModelResult().getResult().isPresent(), is(true));
        Model model = project.getModelResult().getResult().get();
        assertThat(model, hasShapeWithId("smithy.test#test"));
        assertThat(model, hasShapeWithId("ns.test#Weather"));
        assertThat(model.expectShape(ShapeId.from("ns.test#Weather")).hasTrait("smithy.test#test"), is(true));
    }

    @Test
    public void failsLoadingInvalidSmithyBuildJson() {
        Path root = toPath(getClass().getResource("broken/missing-version"));
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void failsLoadingUnparseableSmithyBuildJson() {
        Path root = toPath(getClass().getResource("broken/parse-failure"));
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void doesntFailLoadingProjectWithNonExistingSource() {
        Path root = toPath(getClass().getResource("broken/source-doesnt-exist"));
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(false));
        assertThat(result.unwrap().getSmithyFiles().size(), equalTo(1)); // still have the prelude
    }


    @Test
    public void failsLoadingUnresolvableMavenDependency() {
        Path root = toPath(getClass().getResource("broken/unresolvable-maven-dependency"));
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void failsLoadingUnresolvableProjectDependency() {
        Path root = toPath(getClass().getResource("broken/unresolvable-maven-dependency"));
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void loadsProjectWithUnNormalizedDirs() {
        Path root = toPath(getClass().getResource("unnormalized-dirs"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItems(
                root.resolve("model"),
                root.resolve("model2")));
        assertThat(project.getImports(), hasItem(root.resolve("model3")));
        assertThat(project.getSmithyFiles().keySet(), hasItems(
                equalTo(root.resolve("model/test-traits.smithy").toString()),
                equalTo(root.resolve("model/one.smithy").toString()),
                equalTo(root.resolve("model2/two.smithy").toString()),
                equalTo(root.resolve("model3/three.smithy").toString()),
                containsString("smithy-test-traits.jar!/META-INF/smithy/smithy.test.json")));
        assertThat(project.getDependencies(), hasItem(root.resolve("smithy-test-traits.jar")));
    }

    @Test
    public void changeFileApplyingSimpleTrait() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n"
                    + "apply Bar @length(min: 1)\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changeFileApplyingListTrait() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n"
                    + "apply Bar @tags([\"foo\"])\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
    }

    @Test
    public void changeFileApplyingListTraitWithUnrelatedDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n"
                    + "apply Bar @tags([\"foo\"])\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n"
                    + "string Baz\n";
        String m3 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Baz @length(min: 1)\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        Shape baz = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Baz"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(baz.hasTrait("length"), is(true));
        assertThat(baz.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        baz = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Baz"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(baz.hasTrait("length"), is(true));
        assertThat(baz.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileApplyingListTraitWithRelatedDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n"
                    + "apply Bar @tags([\"foo\"])\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n";
        String m3 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @length(min: 1)\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileApplyingListTraitWithRelatedArrayTraitDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n"
                    + "apply Bar @tags([\"foo\"])\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n";
        String m3 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @tags([\"bar\"])\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));
    }

    @Test
    public void changingFileWithDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n"
                    + "apply Foo @length(min: 1)\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("length"), is(true));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("length"), is(true));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileWithArrayDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n"
                    + "apply Foo @tags([\"foo\"])\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
    }

    @Test
    public void changingFileWithMixedArrayDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "@tags([\"foo\"])\n"
                    + "string Foo\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n"
                    + "apply Foo @tags([\"foo\"])\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "foo"));
    }

    @Test
    public void changingFileWithArrayDependenciesWithDependencies() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Foo\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n"
                    + "apply Foo @tags([\"foo\"])\n";
        String m3 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @length(min: 1)\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        if (document == null) {
            String smithyFilesPaths = String.join(System.lineSeparator(), project.getSmithyFiles().keySet());
            String smithyFilesUris = project.getSmithyFiles().keySet().stream()
                    .map(UriAdapter::toUri)
                    .collect(Collectors.joining(System.lineSeparator()));
            Logger logger = Logger.getLogger(getClass().getName());
            logger.severe("Not found uri: " + uri);
            logger.severe("Not found path: " + UriAdapter.toPath(uri));
            logger.severe("PATHS: " + smithyFilesPaths);
            logger.severe("URIS: " + smithyFilesUris);
        }
        document.applyEdit(RangeAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void removingSimpleApply() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @length(min: 1)\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n";
        String m3 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @pattern(\"a\")\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("pattern"), is(true));
        assertThat(bar.expectTrait(PatternTrait.class).getPattern().pattern(), equalTo("a"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.lineSpan(2, 0, document.lineEnd(2)), "");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("pattern"), is(true));
        assertThat(bar.expectTrait(PatternTrait.class).getPattern().pattern(), equalTo("a"));
        assertThat(bar.hasTrait("length"), is(false));
    }

    @Test
    public void removingArrayApply() {
        String m1 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @tags([\"foo\"])\n";
        String m2 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "string Bar\n";
        String m3 = "$version: \"2\"\n"
                    + "namespace com.foo\n"
                    + "apply Bar @tags([\"bar\"])\n";
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(RangeAdapter.lineSpan(2, 0, document.lineEnd(2)), "");

        project.updateModelWithoutValidating(uri);

        bar = project.getModelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("bar"));
    }

    public static Path toPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
