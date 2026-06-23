/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.smithy.diff.DiffEvaluator;
import software.amazon.smithy.diff.Differences;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidationEventDecorator;
import software.amazon.smithy.model.validation.suppressions.ModelBasedEventDecorator;

/**
 * Runs Smithy {@link DiffEvaluator}s in-process against a baseline ("old") model and the
 * current ("new") model, returning the raw diff {@link ValidationEvent}s.
 *
 * <p>This is the execution core for in-editor diffing. Evaluators are discovered via
 * the SPI {@link ServiceLoader} over the supplied class loader, then run individually rather
 * than through {@code ModelDiff.compare}, which fans them out over a parallel stream with no
 * isolation. Running each evaluator in its own try/catch means a single misbehaving evaluator
 * is logged and skipped without taking down the others or the server  — including an
 * evaluator that fails with a {@link LinkageError} due to Smithy version skew.
 *
 * <p>Resulting events are passed through the new model's {@link ModelBasedEventDecorator}, so
 * diff-event suppressions and severity overrides declared in the model's metadata apply exactly
 * as they do for {@code ModelDiff.compare} — keeping this a faithful, generic diff runner.
 *
 * <p>The {@code evaluatorClassLoader} should be the project's resolved-dependency class loader
 * (the same one {@code ProjectLoader} builds for model assembly). Because that loader's parent
 * is the application class loader, Smithy's own classes ({@code DiffEvaluator},
 * {@link Differences}, {@code ValidationEvent}) resolve to the server's bundled Smithy via
 * parent-first delegation, while custom evaluators load from the dependency jars.
 */
public final class ModelDiffRunner {

    private static final Logger LOGGER = Logger.getLogger(ModelDiffRunner.class.getName());

    private ModelDiffRunner() {
    }

    public static URLClassLoader evaluatorClassLoader(List<URL> resolvedDependencies) {
        return new URLClassLoader(resolvedDependencies.toArray(new URL[0]));
    }

    /**
     * Runs all {@link DiffEvaluator}s discoverable on {@code evaluatorClassLoader} against the
     * two models, isolating each evaluator so one failure can't suppress the rest.
     *
     * @param evaluatorClassLoader class loader scanned for {@link DiffEvaluator} SPI providers
     * @param oldModel the baseline ("previous") model
     * @param newModel the current (edited) model
     * @return the diff validation events produced by the evaluators that ran successfully
     */
    public static List<ValidationEvent> run(ClassLoader evaluatorClassLoader, Model oldModel, Model newModel) {
        Differences differences = Differences.detect(oldModel, newModel);
        // Suppressions and severity overrides come from the new model's metadata, applied to
        // each event just as ModelDiff.compare does.
        ValidationEventDecorator decorator = new ModelBasedEventDecorator()
                .createDecorator(newModel)
                .getResult()
                .orElse(ValidationEventDecorator.IDENTITY);

        List<ValidationEvent> events = new ArrayList<>();
        // Iterate explicitly (rather than stream().forEach) so the try/catch also covers advancing
        // the iterator: a malformed META-INF/services entry (missing class / wrong type) throws a
        // ServiceConfigurationError from hasNext()/next(), before any provider runs. Guarding both
        // the advance and the evaluation contains a single bad provider rather than aborting the
        // whole diff.
        Iterator<DiffEvaluator> iterator =
                ServiceLoader.load(DiffEvaluator.class, evaluatorClassLoader).iterator();
        while (true) {
            DiffEvaluator evaluator;
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                evaluator = iterator.next();
            } catch (ServiceConfigurationError e) {
                // A bad provider can't be skipped individually here (we never got a handle to it),
                // but ServiceLoader's iterator advances past it, so continue to the next one.
                LOGGER.log(Level.WARNING, "Skipping DiffEvaluator that failed to load", e);
                continue;
            }
            try {
                for (ValidationEvent event : evaluator.evaluate(differences)) {
                    events.add(decorator.decorate(event));
                }
            } catch (RuntimeException | LinkageError | ServiceConfigurationError e) {
                LOGGER.log(Level.WARNING, e,
                        () -> "Skipping DiffEvaluator that failed: " + evaluator.getClass().getName());
            }
        }
        return events;
    }
}
