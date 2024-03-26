/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static software.amazon.smithy.lsp.SmithyMatchers.hasShapeWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.hasMessage;
import static software.amazon.smithy.lsp.document.DocumentTest.string;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.protocol.UriAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ProjectTest {
    @Test
    public void loadsFlatProject() {
        Path root = Paths.get(getClass().getResource("flat").getPath());
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
        Path root = Paths.get(getClass().getResource("maven-dep").getPath());
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
        Path root = Paths.get(getClass().getResource("subdirs").getPath());
        Project project = ProjectLoader.load(root).unwrap();

        assertThat(project.getRoot(), equalTo(root));
        assertThat(project.getSources(), hasItem(root.resolve("model")));
        assertThat(project.getModelResult().isBroken(), is(false));
        assertThat(project.getModelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsModelWithUnknownTrait() {
        Path root = Paths.get(getClass().getResource("unknown-trait").getPath());
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
        Path root = Paths.get(getClass().getResource("invalid-syntax").getPath());
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
        Path root = Paths.get(getClass().getResource("multiple-namespaces").getPath());
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
        Path root = Paths.get(getClass().getResource("external-jars").getPath());
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
        assertThat(project.getModelResult().getValidationEvents(Severity.ERROR), hasItem(hasMessage(containsString("Proto index 1"))));

        assertThat(project.getModelResult().getResult().isPresent(), is(true));
        Model model = project.getModelResult().getResult().get();
        assertThat(model, hasShapeWithId("smithy.test#test"));
        assertThat(model, hasShapeWithId("ns.test#Weather"));
        assertThat(model.expectShape(ShapeId.from("ns.test#Weather")).hasTrait("smithy.test#test"), is(true));
    }

    @Test
    public void failsLoadingInvalidSmithyBuildJson() {
        Path root = Paths.get(getClass().getResource("broken/missing-version").getPath());
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void failsLoadingUnparseableSmithyBuildJson() {
        Path root = Paths.get(getClass().getResource("broken/parse-failure").getPath());
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void failsLoadingProjectWithNonExistingSource() {
        Path root = Paths.get(getClass().getResource("broken/source-doesnt-exist").getPath());
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }


    @Test
    public void failsLoadingUnresolvableMavenDependency() {
        Path root = Paths.get(getClass().getResource("broken/unresolvable-maven-dependency").getPath());
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }

    @Test
    public void failsLoadingUnresolvableProjectDependency() {
        Path root = Paths.get(getClass().getResource("broken/unresolvable-maven-dependency").getPath());
        Result<Project, List<Exception>> result = ProjectLoader.load(root);

        assertThat(result.isErr(), is(true));
    }
}
