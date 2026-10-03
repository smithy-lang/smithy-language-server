/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import java.net.URLClassLoader;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ModelDiffRunnerTest {

    @Test
    public void runsEvaluatorsAndReturnsDiffEvents() {
        // The current class loader carries smithy-diff's stock evaluators, so a removed shape
        // should surface as a `RemovedShape` event without any custom evaluator on the path.
        Model oldModel = assemble("old.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }",
                "structure Removed {}"));
        Model newModel = assemble("new.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }"));

        List<ValidationEvent> events =
                ModelDiffRunner.run(getClass().getClassLoader(), oldModel, newModel);

        List<String> shapeIdsForRemoved = events.stream()
                .filter(e -> e.getId().startsWith("RemovedShape"))
                .map(e -> e.getShapeId().map(Object::toString).orElse(""))
                .collect(Collectors.toList());
        assertThat(shapeIdsForRemoved, hasItem("example#Removed"));
    }

    @Test
    public void identicalModelsProduceNoEvents() {
        String idl = String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }");
        Model oldModel = assemble("old.smithy", idl);
        Model newModel = assemble("new.smithy", idl);

        List<ValidationEvent> events =
                ModelDiffRunner.run(getClass().getClassLoader(), oldModel, newModel);

        assertThat(events, is(empty()));
    }

    @Test
    public void classLoaderWithNoDepsStillDiscoversStockEvaluators() throws Exception {
        Model oldModel = assemble("old.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }",
                "structure Removed {}"));
        Model newModel = assemble("new.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }"));

        // Empty deps -> the loader's parent (the app class loader) still carries smithy-diff's
        // stock evaluators, so a removed shape is still detected.
        try (URLClassLoader loader = ModelDiffRunner.evaluatorClassLoader(List.of())) {
            List<ValidationEvent> events = ModelDiffRunner.run(loader, oldModel, newModel);
            List<String> removedIds = events.stream()
                    .filter(e -> e.getId().startsWith("RemovedShape"))
                    .map(e -> e.getShapeId().map(Object::toString).orElse(""))
                    .collect(Collectors.toList());
            assertThat(removedIds, hasItem("example#Removed"));
        }
    }

    @Test
    public void appliesSuppressionsFromNewModelMetadata() {
        Model oldModel = assemble("old.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }"));
        // The new model adds a shape (AddedShape is a NOTE, which is suppressible — unlike the
        // ERROR-severity RemovedShape) and suppresses AddedShape events in `example` via metadata.
        Model newModel = assemble("new.smithy", String.join("\n",
                "$version: \"2.0\"",
                "metadata suppressions = [{ id: \"AddedShape\", namespace: \"example\", reason: \"ok\" }]",
                "namespace example",
                "structure Foo { keep: String }",
                "structure Added {}"));

        List<ValidationEvent> events =
                ModelDiffRunner.run(getClass().getClassLoader(), oldModel, newModel);

        ValidationEvent added = events.stream()
                .filter(e -> e.getId().startsWith("AddedShape")
                             && e.getShapeId().map(id -> id.toString().equals("example#Added")).orElse(false))
                .findFirst()
                .orElseThrow();
        assertThat(added.getSeverity(), is(Severity.SUPPRESSED));
    }

    private static Model assemble(String name, String idl) {
        return Model.assembler().addUnparsedModel(name, idl).assemble().unwrap();
    }
}
