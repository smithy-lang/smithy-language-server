/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class DiffEventAnchoringTest {

    private static final String BUILD_FILE = "/p/smithy-build.json";

    @Test
    public void anchoredEventPassesThroughUnchanged() {
        Model model = singleFileModel();
        // An event already pointing at a current file (e.g. a changed shape) is left alone.
        ValidationEvent event = event("ChangedShapeType", "example#Foo", "/p/a.smithy", 3, 1);

        List<ValidationEvent> result = DiffEventAnchoring.anchor(List.of(event), model, BUILD_FILE);

        assertThat(result.get(0), sameInstance(event));
    }

    @Test
    public void removedShapeAnchoredToNamespaceFileAtTop() {
        Model model = singleFileModel();
        // example#Gone no longer exists in the current model; its location is in the baseline.
        ValidationEvent event = event("RemovedShape", "example#Gone", "/baseline/old.smithy", 9, 5);

        SourceLocation anchored = DiffEventAnchoring.anchor(List.of(event), model, BUILD_FILE)
                .get(0).getSourceLocation();

        assertThat(anchored.getFilename(), is("/p/a.smithy")); // namespace `example` lives here
        assertThat(anchored.getLine(), is(1));
        assertThat(anchored.getColumn(), is(1));
    }

    @Test
    public void fallsBackToBuildFileWhenNamespaceNotInCurrentModel() {
        Model model = singleFileModel();
        ValidationEvent event = event("RemovedShape", "other#Gone", "/baseline/old.smithy", 9, 5);

        SourceLocation anchored = DiffEventAnchoring.anchor(List.of(event), model, BUILD_FILE)
                .get(0).getSourceLocation();

        assertThat(anchored.getFilename(), is(BUILD_FILE));
        assertThat(anchored.getLine(), is(1));
        assertThat(anchored.getColumn(), is(1));
    }

    @Test
    public void fallsBackToBuildFileWhenEventHasNoShapeId() {
        Model model = singleFileModel();
        ValidationEvent event = ValidationEvent.builder()
                .id("RemovedMetadata")
                .severity(Severity.WARNING)
                .message("removed metadata")
                .sourceLocation(new SourceLocation("/baseline/old.smithy", 1, 1))
                .build();

        SourceLocation anchored = DiffEventAnchoring.anchor(List.of(event), model, BUILD_FILE)
                .get(0).getSourceLocation();

        assertThat(anchored.getFilename(), is(BUILD_FILE));
    }

    @Test
    public void namespaceSpanningFilesAnchorsToLexicographicallySmallest() {
        Model model = Model.assembler()
                .addUnparsedModel("/p/b.smithy", "$version: \"2.0\"\nnamespace example\nstructure Bar {}\n")
                .addUnparsedModel("/p/a.smithy", "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n")
                .assemble()
                .unwrap();
        ValidationEvent event = event("RemovedShape", "example#Gone", "/baseline/old.smithy", 9, 5);

        SourceLocation anchored = DiffEventAnchoring.anchor(List.of(event), model, BUILD_FILE)
                .get(0).getSourceLocation();

        assertThat(anchored.getFilename(), is("/p/a.smithy"));
    }

    private static Model singleFileModel() {
        return Model.assembler()
                .addUnparsedModel("/p/a.smithy", "$version: \"2.0\"\nnamespace example\nstructure Foo { id: String }\n")
                .assemble()
                .unwrap();
    }

    private static ValidationEvent event(String id, String shapeId, String filename, int line, int column) {
        return ValidationEvent.builder()
                .id(id)
                .severity(Severity.DANGER)
                .message("diff event")
                .shapeId(ShapeId.from(shapeId))
                .sourceLocation(new SourceLocation(filename, line, column))
                .build();
    }
}
