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
        return run(discoverEvaluators(evaluatorClassLoader), oldModel, newModel);
    }

    /**
     * Discovers all {@link DiffEvaluator} SPI providers on the class loader, skipping (and
     * logging) providers that fail to load so a single bad entry can't suppress the rest.
     *
     * <p>Discovery is separated from {@link #run(List, Model, Model)} so callers can cache the
     * instantiated evaluators alongside the class loader instead of re-scanning
     * {@code META-INF/services} and re-instantiating every evaluator on each diff.
     *
     * @param evaluatorClassLoader class loader scanned for {@link DiffEvaluator} SPI providers
     * @return the evaluators that loaded successfully
     */
    public static List<DiffEvaluator> discoverEvaluators(ClassLoader evaluatorClassLoader) {
        List<DiffEvaluator> evaluators = new ArrayList<>();
        Iterator<DiffEvaluator> iterator =
                ServiceLoader.load(DiffEvaluator.class, evaluatorClassLoader).iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                // A bad provider can't be skipped individually in the catch (we never got a
                // handle to it), but ServiceLoader's iterator advances past it, so the loop
                // just continues to the next one.
                evaluators.add(iterator.next());
            } catch (ServiceConfigurationError | LinkageError e) {
                LOGGER.log(Level.WARNING, "Skipping DiffEvaluator that failed to load", e);
            }
        }
        return evaluators;
    }

    /**
     * Runs the given evaluators against the two models, isolating each evaluator so one failure
     * can't suppress the rest.
     *
     * @param evaluators the evaluators to run (see {@link #discoverEvaluators})
     * @param oldModel the baseline ("previous") model
     * @param newModel the current (edited) model
     * @return the diff validation events produced by the evaluators that ran successfully
     */
    public static List<ValidationEvent> run(List<DiffEvaluator> evaluators, Model oldModel, Model newModel) {
        Differences differences = Differences.detect(oldModel, newModel);
        // Suppressions and severity overrides come from the new model's metadata, applied to
        // each event just as ModelDiff.compare does.
        ValidationEventDecorator decorator = new ModelBasedEventDecorator()
                .createDecorator(newModel)
                .getResult()
                .orElse(ValidationEventDecorator.IDENTITY);

        List<ValidationEvent> events = new ArrayList<>();
        for (DiffEvaluator evaluator : evaluators) {
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
