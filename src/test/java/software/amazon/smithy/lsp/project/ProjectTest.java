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
import software.amazon.smithy.lsp.protocol.LspAdapter;
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

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.imports(), empty());
        assertThat(project.dependencies(), empty());
        assertThat(project.modelResult().isBroken(), is(false));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsProjectWithMavenDep() {
        Path root = toPath(getClass().getResource("maven-dep"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.imports(), empty());
        assertThat(project.dependencies(), hasSize(3));
        assertThat(project.modelResult().isBroken(), is(false));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsProjectWithSubdir() {
        Path root = toPath(getClass().getResource("subdirs"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItems(
                root.resolve("model"),
                root.resolve("model2")));
        assertThat(project.smithyFiles().keySet(), hasItems(
                equalTo(root.resolve("model/main.smithy").toString()),
                equalTo(root.resolve("model/subdir/sub.smithy").toString()),
                equalTo(root.resolve("model2/subdir2/sub2.smithy").toString()),
                equalTo(root.resolve("model2/subdir2/subsubdir/subsub.smithy").toString())));
        assertThat(project.modelResult().isBroken(), is(false));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Bar"));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Baz"));
    }

    @Test
    public void loadsModelWithUnknownTrait() {
        Path root = toPath(getClass().getResource("unknown-trait"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.modelResult().isBroken(), is(false)); // unknown traits don't break it

        List<String> eventIds = project.modelResult().getValidationEvents().stream()
                .map(ValidationEvent::getId)
                .collect(Collectors.toList());
        assertThat(eventIds, hasItem(containsString("UnresolvedTrait")));
        assertThat(project.modelResult().getResult().isPresent(), is(true));
        assertThat(project.modelResult().getResult().get(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsWhenModelHasInvalidSyntax() {
        Path root = toPath(getClass().getResource("invalid-syntax"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItem(root.resolve("main.smithy")));
        assertThat(project.modelResult().isBroken(), is(true));
        List<String> eventIds = project.modelResult().getValidationEvents().stream()
                .map(ValidationEvent::getId)
                .collect(Collectors.toList());
        assertThat(eventIds, hasItem("Model"));

        assertThat(project.smithyFiles().keySet(), hasItem(containsString("main.smithy")));
        SmithyFile main = project.getSmithyFile(LspAdapter.toUri(root.resolve("main.smithy").toString()));
        assertThat(main, not(nullValue()));
        assertThat(main.document(), not(nullValue()));
        assertThat(main.namespace(), string("com.foo"));
        assertThat(main.imports(), empty());

        assertThat(main.shapes(), hasSize(2));
        List<String> shapeIds = main.shapes().stream()
                .map(Shape::toShapeId)
                .map(ShapeId::toString)
                .collect(Collectors.toList());
        assertThat(shapeIds, hasItems("com.foo#Foo", "com.foo#Foo$bar"));

        assertThat(main.documentShapes(), hasSize(3));
        List<String> documentShapeNames = main.documentShapes().stream()
                .map(documentShape -> documentShape.shapeName().toString())
                .collect(Collectors.toList());
        assertThat(documentShapeNames, hasItems("Foo", "bar", "String"));
    }

    @Test
    public void loadsProjectWithMultipleNamespaces() {
        Path root = toPath(getClass().getResource("multiple-namespaces"));
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.sources(), hasItem(root.resolve("model")));
        assertThat(project.modelResult().getValidationEvents(), empty());
        assertThat(project.smithyFiles().keySet(), hasItems(containsString("a.smithy"), containsString("b.smithy")));

        SmithyFile a = project.getSmithyFile(LspAdapter.toUri(root.resolve("model/a.smithy").toString()));
        assertThat(a.document(), not(nullValue()));
        assertThat(a.namespace(), string("a"));
        List<String> aShapeIds = a.shapes().stream()
                .map(Shape::toShapeId)
                .map(ShapeId::toString)
                .collect(Collectors.toList());
        assertThat(aShapeIds, hasItems("a#Hello", "a#HelloInput", "a#HelloOutput"));
        List<String> aDocumentShapeNames = a.documentShapes().stream()
                .map(documentShape -> documentShape.shapeName().toString())
                .collect(Collectors.toList());
        assertThat(aDocumentShapeNames, hasItems("Hello", "name", "String"));

        SmithyFile b = project.getSmithyFile(LspAdapter.toUri(root.resolve("model/b.smithy").toString()));
        assertThat(b.document(), not(nullValue()));
        assertThat(b.namespace(), string("b"));
        List<String> bShapeIds = b.shapes().stream()
                .map(Shape::toShapeId)
                .map(ShapeId::toString)
                .collect(Collectors.toList());
        assertThat(bShapeIds, hasItems("b#Hello", "b#HelloInput", "b#HelloOutput"));
        List<String> bDocumentShapeNames = b.documentShapes().stream()
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
        assertThat(project.sources(), containsInAnyOrder(root.resolve("test-traits.smithy"), root.resolve("test-validators.smithy")));
        assertThat(project.smithyFiles().keySet(), hasItems(
                containsString("test-traits.smithy"),
                containsString("test-validators.smithy"),
                containsString("smithy-test-traits.jar!/META-INF/smithy/smithy.test.json"),
                containsString("alloy-core.jar!/META-INF/smithy/uuid.smithy")));

        assertThat(project.modelResult().isBroken(), is(true));
        assertThat(project.modelResult().getValidationEvents(Severity.ERROR), hasItem(eventWithMessage(containsString("Proto index 1"))));

        assertThat(project.modelResult().getResult().isPresent(), is(true));
        Model model = project.modelResult().getResult().get();
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
        assertThat(result.unwrap().smithyFiles().size(), equalTo(1)); // still have the prelude
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

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItems(
                root.resolve("model"),
                root.resolve("model2")));
        assertThat(project.imports(), hasItem(root.resolve("model3")));
        assertThat(project.smithyFiles().keySet(), hasItems(
                equalTo(root.resolve("model/test-traits.smithy").toString()),
                equalTo(root.resolve("model/one.smithy").toString()),
                equalTo(root.resolve("model2/two.smithy").toString()),
                equalTo(root.resolve("model3/three.smithy").toString()),
                containsString("smithy-test-traits.jar!/META-INF/smithy/smithy.test.json")));
        assertThat(project.dependencies(), hasItem(root.resolve("smithy-test-traits.jar")));
    }

    @Test
    public void changeFileApplyingSimpleTrait() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @length(min: 1)
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changeFileApplyingListTrait() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
    }

    @Test
    public void changeFileApplyingListTraitWithUnrelatedDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                string Baz
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Baz @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        Shape baz = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Baz"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(baz.hasTrait("length"), is(true));
        assertThat(baz.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        baz = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Baz"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(baz.hasTrait("length"), is(true));
        assertThat(baz.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileApplyingListTraitWithRelatedDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileApplyingListTraitWithRelatedArrayTraitDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @tags(["bar"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));
    }

    @Test
    public void changingFileWithDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("length"), is(true));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("length"), is(true));
        assertThat(foo.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void changingFileWithArrayDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @tags(["foo"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
    }

    @Test
    public void changingFileWithMixedArrayDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                @tags(["foo"])
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @tags(["foo"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "foo"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "foo"));
    }

    @Test
    public void changingFileWithArrayDependenciesWithDependencies() {
        String m1 = """
                $version: "2"
                namespace com.foo
                string Foo
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                apply Foo @tags(["foo"])
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @length(min: 1)
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        if (document == null) {
            String smithyFilesPaths = String.join(System.lineSeparator(), project.smithyFiles().keySet());
            String smithyFilesUris = project.smithyFiles().keySet().stream()
                    .map(LspAdapter::toUri)
                    .collect(Collectors.joining(System.lineSeparator()));
            Logger logger = Logger.getLogger(getClass().getName());
            logger.severe("Not found uri: " + uri);
            logger.severe("Not found path: " + LspAdapter.toPath(uri));
            logger.severe("PATHS: " + smithyFilesPaths);
            logger.severe("URIS: " + smithyFilesUris);
        }
        document.applyEdit(LspAdapter.point(document.end()), "\n");

        project.updateModelWithoutValidating(uri);

        foo = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Foo"));
        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(foo.hasTrait("tags"), is(true));
        assertThat(foo.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));
    }

    @Test
    public void removingSimpleApply() {
        String m1 = """
                $version: "2"
                namespace com.foo
                apply Bar @length(min: 1)
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @pattern("a")
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("pattern"), is(true));
        assertThat(bar.expectTrait(PatternTrait.class).getPattern().pattern(), equalTo("a"));
        assertThat(bar.hasTrait("length"), is(true));
        assertThat(bar.expectTrait(LengthTrait.class).getMin(), anOptionalOf(equalTo(1L)));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.lineSpan(2, 0, document.lineEnd(2)), "");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("pattern"), is(true));
        assertThat(bar.expectTrait(PatternTrait.class).getPattern().pattern(), equalTo("a"));
        assertThat(bar.hasTrait("length"), is(false));
    }

    @Test
    public void removingArrayApply() {
        String m1 = """
                $version: "2"
                namespace com.foo
                apply Bar @tags(["foo"])
                """;
        String m2 = """
                $version: "2"
                namespace com.foo
                string Bar
                """;
        String m3 = """
                $version: "2"
                namespace com.foo
                apply Bar @tags(["bar"])
                """;
        TestWorkspace workspace = TestWorkspace.multipleModels(m1, m2, m3);
        Project project = ProjectLoader.load(workspace.getRoot()).unwrap();

        Shape bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
        assertThat(bar.hasTrait("tags"), is(true));
        assertThat(bar.expectTrait(TagsTrait.class).getTags(), containsInAnyOrder("foo", "bar"));

        String uri = workspace.getUri("model-0.smithy");
        Document document = project.getDocument(uri);
        document.applyEdit(LspAdapter.lineSpan(2, 0, document.lineEnd(2)), "");

        project.updateModelWithoutValidating(uri);

        bar = project.modelResult().unwrap().expectShape(ShapeId.from("com.foo#Bar"));
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
