/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.eventWithMessage;
import static software.amazon.smithy.lsp.SmithyMatchers.hasShapeWithId;
import static software.amazon.smithy.lsp.SmithyMatchers.hasValue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.Severity;

public class ProjectLoaderTest {
    @Test
    public void loadsFlatProject() {
        Path root = ProjectTest.toPath(getClass().getResource("flat"));
        Project project = ProjectTest.load(root);

        assertThat(project.root(), equalTo(root));
        assertThat(project.config().sources(), hasItem(endsWith("main.smithy")));
        assertThat(project.config().imports(), empty());
        assertThat(project.config().resolvedDependencies(), empty());
        assertThat(project.modelResult().isBroken(), is(false));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsProjectWithMavenDep() {
        Path root = ProjectTest.toPath(getClass().getResource("maven-dep"));
        Project project = ProjectTest.load(root);

        assertThat(project.root(), equalTo(root));
        assertThat(project.config().sources(), hasItem(endsWith("main.smithy")));
        assertThat(project.config().imports(), empty());
        assertThat(project.config().resolvedDependencies(), hasSize(3));
        assertThat(project.modelResult().isBroken(), is(false));
        assertThat(project.modelResult().unwrap(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsProjectWithSubdir() {
        Path root = ProjectTest.toPath(getClass().getResource("subdirs"));
        Project project = ProjectTest.load(root);

        assertThat(project.root(), equalTo(root));
        assertThat(project.config().sources(), hasItems(
                endsWith("model"),
                endsWith("model2")));
        assertThat(project.getAllSmithyFilePaths(), hasItems(
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
        Path root = ProjectTest.toPath(getClass().getResource("unknown-trait"));
        Project project = ProjectTest.load(root);

        assertThat(project.root(), equalTo(root));
        assertThat(project.config().sources(), hasItem(endsWith("main.smithy")));
        assertThat(project.modelResult().isBroken(), is(false)); // unknown traits don't break it
        assertThat(project.modelResult().getValidationEvents(), hasItem(eventWithId(containsString("UnresolvedTrait"))));
        assertThat(project.modelResult().getResult().isPresent(), is(true));
        assertThat(project.modelResult().getResult().get(), hasShapeWithId("com.foo#Foo"));
    }

    @Test
    public void loadsWhenModelHasInvalidSyntax() {
        Path root = ProjectTest.toPath(getClass().getResource("invalid-syntax"));
        Project project = ProjectTest.load(root);

        assertThat(project.root(), equalTo(root));
        assertThat(project.config().sources(), hasItem(endsWith("main.smithy")));
        assertThat(project.modelResult().isBroken(), is(true));
        assertThat(project.modelResult().getValidationEvents(), hasItem(eventWithId(equalTo("Model"))));
        assertThat(project.modelResult(), hasValue(allOf(
                hasShapeWithId("com.foo#Foo"),
                hasShapeWithId("com.foo#Foo$bar"))));
        assertThat(project.getAllSmithyFilePaths(), hasItem(containsString("main.smithy")));
    }

    @Test
    public void loadsProjectWithMultipleNamespaces() {
        Path root = ProjectTest.toPath(getClass().getResource("multiple-namespaces"));
        Project project = ProjectTest.load(root);

        assertThat(project.config().sources(), hasItem(endsWith("model")));
        assertThat(project.modelResult().getValidationEvents(), empty());
        assertThat(project.getAllSmithyFilePaths(), hasItems(containsString("a.smithy"), containsString("b.smithy")));

        assertThat(project.modelResult(), hasValue(allOf(
                hasShapeWithId("a#Hello"),
                hasShapeWithId("a#HelloInput"),
                hasShapeWithId("a#HelloOutput"),
                hasShapeWithId("b#Hello"),
                hasShapeWithId("b#HelloInput"),
                hasShapeWithId("b#HelloOutput"))));
    }

    @Test
    public void loadsProjectWithExternalJars() {
        Path root = ProjectTest.toPath(getClass().getResource("external-jars"));
        Project project = ProjectTest.load(root);

        assertThat(project.config().sources(), containsInAnyOrder(
                endsWith("test-traits.smithy"),
                endsWith("test-validators.smithy")));
        assertThat(project.getAllSmithyFilePaths(), hasItems(
                containsString("test-traits.smithy"),
                containsString("test-validators.smithy"),
                // Note: Depending on the order of how jar dependencies are added to the model assembler,
                //  this may or may not be present. This is because we're relying on the shapes loaded in
                //  the model to determine all Smithy files, and this file re-defines a shape, so the shape
                //  definition is super-seeded.
                // containsString("smithy-test-traits.jar!/META-INF/smithy/smithy.test.json"),
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
    public void loadsProjectWithInvalidSmithyBuildJson() {
        Path root = ProjectTest.toPath(getClass().getResource("broken/missing-version"));
        Project project = ProjectTest.load(root);

        assertHasBuildFile(project, BuildFileType.SMITHY_BUILD);
        assertThat(project.configEvents(), hasItem(eventWithMessage(containsString("version"))));
        assertThat(project.modelResult().isBroken(), is(false));
    }

    @Test
    public void loadsProjectWithUnparseableSmithyBuildJson() {
        Path root = ProjectTest.toPath(getClass().getResource("broken/parse-failure"));
        Project project = ProjectTest.load(root);

        assertHasBuildFile(project, BuildFileType.SMITHY_BUILD);
        assertThat(project.configEvents().isEmpty(), is(false));
        assertThat(project.modelResult().isBroken(), is(false));
    }

    @Test
    public void loadsProjectWithNonExistingSource() {
        Path root = ProjectTest.toPath(getClass().getResource("broken/source-doesnt-exist"));
        Project project = ProjectTest.load(root);

        assertHasBuildFile(project, BuildFileType.SMITHY_BUILD);
        assertThat(project.configEvents(), hasItem(eventWithId(equalTo("FileNotFound"))));
        assertThat(project.modelResult().isBroken(), is(false));
        assertThat(project.getAllSmithyFiles().size(), equalTo(1)); // still have the prelude
    }

    @Test
    public void loadsProjectWithUnresolvableMavenDependency() {
        Path root = ProjectTest.toPath(getClass().getResource("broken/unresolvable-maven-dependency"));
        Project project = ProjectTest.load(root);

        assertHasBuildFile(project, BuildFileType.SMITHY_BUILD);
        assertThat(project.configEvents(), hasItem(eventWithId(equalTo("DependencyResolver"))));
        assertThat(project.modelResult().isBroken(), is(false));
    }

    @Test
    public void loadsProjectWithUnresolvableProjectDependency() {
        Path root = ProjectTest.toPath(getClass().getResource("broken/unresolvable-project-dependency"));
        Project project = ProjectTest.load(root);

        assertHasBuildFile(project, BuildFileType.SMITHY_PROJECT);
        assertThat(project.configEvents(), hasItem(eventWithId(equalTo("FileNotFound"))));
        assertThat(project.modelResult().isBroken(), is(false));
    }

    @Test
    public void loadsProjectWithUnNormalizedDirs() throws Exception {
        Path root = ProjectTest.toPath(getClass().getResource("unnormalized-dirs"));
        Project project = ProjectTest.load(root);

        assertThat(project.root(), equalTo(root));
        assertThat(project.sources(), hasItems(
                root.resolve("model"),
                root.resolve("model2")));
        assertThat(project.imports(), hasItem(root.resolve("model3")));
        assertThat(project.getAllSmithyFilePaths(), hasItems(
                equalTo(root.resolve("model/test-traits.smithy").toString()),
                equalTo(root.resolve("model/one.smithy").toString()),
                equalTo(root.resolve("model2/two.smithy").toString()),
                equalTo(root.resolve("model3/three.smithy").toString()),
                containsString("smithy-test-traits.jar!/META-INF/smithy/smithy.test.json")));
        assertThat(project.config().resolvedDependencies(), hasItem(
                root.resolve("smithy-test-traits.jar").toUri().toURL()));
    }

    private static void assertHasBuildFile(Project project, BuildFileType expectedType) {
        String uri = LspAdapter.toUri(project.root().resolve(expectedType.filename()).toString());
        var file = project.getProjectFile(uri);
        assertThat(file, instanceOf(BuildFile.class));
        assertThat(((BuildFile) file).type(), is(expectedType));
    }
}
