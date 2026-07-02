/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.lsp.diff.BaselineModelException;
import software.amazon.smithy.lsp.diff.BaselineProvider;
import software.amazon.smithy.lsp.diff.DiffConfig;
import software.amazon.smithy.lsp.diff.DiffEvaluatorFilter;
import software.amazon.smithy.lsp.diff.DiffEventAnchoring;
import software.amazon.smithy.lsp.diff.MavenBaselineProvider;
import software.amazon.smithy.lsp.diff.ModelDiffRunner;
import software.amazon.smithy.lsp.diff.UrlBaselineProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Runs the configured diff for a project and stores the resulting (re-anchored) events on it.
 * Holds a per-project {@link DiffContext} so the expensive baseline assembly and evaluator class loader
 * are paid once and reused across saves  and serializes diff work per project so concurrent saves/reloads don't race.
 *
 * A failure to resolve/read the baseline (a user-fixable config problem) is surfaced loudly as a diagnostic
 * on the {@code .smithy-project.json} file.
 * Transient runtime problems (the current model not assembling, an evaluator throwing) are
 * logged and the cycle is skipped quietly, leaving the previous diagnostics in place.
 */
public final class ProjectDiffer {

    /** The id of diff diagnostics reporting a baseline configuration/resolution failure. */
    public static final String BASELINE_ERROR_ID = "DiffBaseline";

    private static final Logger LOGGER = Logger.getLogger(ProjectDiffer.class.getName());

    // Maps a parsed baseline config to its provider. Baseline types other than the supported ones
    // are rejected at config parse time (DiffConfig.Baseline.fromNode), so the default branch is
    // defensive and not normally reached.
    private static final BaselineProviderFactory DEFAULT_FACTORY = (baseline, repositories) ->
            switch (baseline.type()) {
                case DiffConfig.Baseline.MAVEN ->
                        new MavenBaselineProvider(baseline.coordinate(), new ArrayList<>(repositories),
                                baseline.transitiveDependencies());
                case DiffConfig.Baseline.URL ->
                        new UrlBaselineProvider(baseline.url());
                default -> throw new BaselineModelException(
                        "Unsupported diff baseline type '" + baseline.type() + "'");
            };

    // One context per project root, holding that project's diff lock, baseline provider, and
    // evaluator class loader. ConcurrentHashMap.computeIfAbsent gives a single shared context
    // per root; the context's own lock serializes the actual diff work.
    private final Map<String, DiffContext> contextsByRoot = new ConcurrentHashMap<>();
    private final BaselineProviderFactory providerFactory;
    private final Consumer<String> configErrorNotifier;

    public ProjectDiffer() {
        this(DEFAULT_FACTORY, message -> { });
    }

    /**
     * @param configErrorNotifier invoked once per distinct baseline config error (e.g. to show a
     *  window message); not called again until the error changes or clears
     */
    public ProjectDiffer(Consumer<String> configErrorNotifier) {
        this(DEFAULT_FACTORY, configErrorNotifier);
    }

    /**
     * @param providerFactory creates the {@link BaselineProvider} for a coordinate (the seam for
     *  diffing without a network)
     * @param configErrorNotifier invoked once per distinct baseline config error
     */
    public ProjectDiffer(BaselineProviderFactory providerFactory, Consumer<String> configErrorNotifier) {
        this.providerFactory = providerFactory;
        this.configErrorNotifier = configErrorNotifier;
    }

    /** Creates a {@link BaselineProvider} for a parsed baseline; the seam for testing without a network. */
    @FunctionalInterface
    public interface BaselineProviderFactory {
        /**
         * @param baseline the parsed baseline config
         * @param repositories repositories to resolve a {@code maven} baseline from
         */
        BaselineProvider create(
                DiffConfig.Baseline baseline,
                Collection<MavenRepository> repositories);
    }

    /**
     * Runs the diff for the project if it has a {@code diff} config, storing the result via
     * {@link Project#setDiffEvents}. Clears diff events if the feature is not configured.
     *
     * <p>Work for a single project is serialized: concurrent {@code runDiff}/{@link #reload}
     * calls for the same root run one at a time, so the events published always reflect the
     * model that the winning cycle diffed (no last-writer-wins from a stale model), and the
     * config-error dedupe stays consistent.
     *
     * @param project the project to diff
     * @return the files whose diff diagnostics may have changed (the union of files referenced by
     *  the previous and new diff events when those differ); empty when the diff events are
     *  unchanged. Lets callers skip a workspace-wide diagnostic refresh when nothing changed
     *  (finding #11). Never used to drop a refresh that's actually needed.
     */
    public Set<String> runDiff(Project project) {
        String root = project.root().toString();
        DiffContext context = contextsByRoot.computeIfAbsent(root, ignored -> new DiffContext());
        context.lock.lock();
        try {
            // The context may have been evicted (project removed) between computeIfAbsent and
            // acquiring the lock. Building anything on the orphaned context would leak a class
            // loader nothing will ever close, so skip quietly — the project is going away.
            if (contextsByRoot.get(root) != context) {
                return Set.of();
            }
            return runDiffLocked(project, root, context);
        } finally {
            context.lock.unlock();
        }
    }

    private Set<String> runDiffLocked(Project project, String root, DiffContext context) {
        List<ValidationEvent> previousEvents = project.diffEvents();

        Optional<DiffConfig> maybeConfig = project.diffConfig();
        if (maybeConfig.isEmpty()) {
            project.setDiffEvents(List.of());
            context.reset();
            return changedFiles(previousEvents, List.of());
        }
        DiffConfig config = maybeConfig.get();

        // Resolve/assemble the baseline before checking the current model, so a baseline config
        // error is surfaced (or cleared) on its own merits regardless of whether the edited model
        // happens to assemble this cycle. Otherwise a fixed coordinate could keep showing a stale
        // baseline error whenever the save lands while the model is mid-edit.
        Model baselineModel;
        try {
            context.ensureBaseline(project, config, providerFactory);
            ValidatedResult<Model> result = context.provider.loadBaseline();
            baselineModel = result.getResult().orElse(null);
        } catch (BaselineModelException e) {
            // Config-loud: the baseline coordinate is unresolvable/malformed — user-fixable.
            List<ValidationEvent> errorEvents = surfaceBaselineConfigError(project, context, e);
            return changedFiles(previousEvents, errorEvents);
        } catch (RuntimeException e) {
            // Runtime-quiet: an unexpected failure assembling the baseline must not break the
            // save flow — skip this cycle and keep the previous diff events.
            LOGGER.log(Level.WARNING, "Skipping diff: failed to load baseline", e);
            return Set.of();
        }
        if (baselineModel == null) {
            LOGGER.warning("Skipping diff: baseline assembled to no model");
            return Set.of();
        }

        Model currentModel = project.modelResult().getResult().orElse(null);
        if (currentModel == null) {
            // Runtime-quiet: the edited model can't assemble, so we can't diff. The baseline loaded
            // fine above, so clear any stale baseline config error (dedupe + the published error
            // event) while keeping genuine prior diff events, which we can't recompute right now.
            LOGGER.fine("Skipping diff: current model did not assemble");
            context.clearConfigError();
            List<ValidationEvent> retained = previousEvents.stream()
                    .filter(event -> !BASELINE_ERROR_ID.equals(event.getId()))
                    .toList();
            if (!retained.equals(previousEvents)) {
                project.setDiffEvents(retained);
                return changedFiles(previousEvents, retained);
            }
            return Set.of();
        }

        List<ValidationEvent> anchored;
        try {
            URLClassLoader classLoader = context.evaluatorClassLoader(project);
            List<ValidationEvent> rawEvents = ModelDiffRunner.run(classLoader, baselineModel, currentModel);
            List<ValidationEvent> diffEvents =
                    new DiffEvaluatorFilter(config.enabledEvaluators(), config.disabledEvaluators())
                            .filter(rawEvents);
            anchored = DiffEventAnchoring.anchor(diffEvents, currentModel, diffConfigFilePath(project));
        } catch (RuntimeException | Error e) {
            // Runtime-quiet (ADR 0007): a failure in diff execution, filtering, or anchoring must
            // not break the save flow or suppress ordinary validation diagnostics — skip this
            // cycle and keep the previous diff events.
            LOGGER.log(Level.WARNING, "Skipping diff: diff execution failed", e);
            return Set.of();
        }

        project.setDiffEvents(anchored);
        // Diff succeeded: clear any prior config error so a later failure notifies again.
        context.clearConfigError();
        return changedFiles(previousEvents, anchored);
    }

    // The files whose diff diagnostics may have changed across a run: empty when the event lists
    // are identical (so callers can skip a workspace-wide refresh), otherwise the union of files
    // referenced by the old and new events (every file that gained or lost a diff event).
    // Compares the whole event lists — not per-file sets — so any change (severity, message,
    // anchor) on any event triggers a refresh of all affected files. Correctness over precision.
    private static Set<String> changedFiles(List<ValidationEvent> oldEvents, List<ValidationEvent> newEvents) {
        if (oldEvents.equals(newEvents)) {
            return Set.of();
        }
        Set<String> files = new LinkedHashSet<>();
        for (ValidationEvent event : oldEvents) {
            files.add(event.getSourceLocation().getFilename());
        }
        for (ValidationEvent event : newEvents) {
            files.add(event.getSourceLocation().getFilename());
        }
        return files;
    }

    /**
     * Invalidates the cached baseline for the project (so a moving coordinate is re-fetched)
     * and re-runs the diff. Held under the same per-project lock as {@link #runDiff}, so a
     * concurrent save can't repopulate the cache between the drop and the re-run.
     *
     * @param project the project whose baseline should be reloaded
     */
    public void reload(Project project) {
        String root = project.root().toString();
        DiffContext context = contextsByRoot.computeIfAbsent(root, ignored -> new DiffContext());
        context.lock.lock();
        try {
            // Drop the cached provider/baseline/class loader entirely so the next diff rebuilds
            // from scratch, forcing a fresh resolve + assemble even for a pinned coordinate.
            context.reset();
            runDiffLocked(project, root, context);
        } finally {
            context.lock.unlock();
        }
    }

    /**
     * Eagerly resolves and assembles the project's diff baseline so the (potentially slow) baseline
     * I/O is paid once at server startup rather than on the first save. The provider memoizes the
     * assembled baseline by its resolved jar paths, so the subsequent first {@link #runDiff} reuses
     * it instead of re-resolving.
     *
     * <p>A no-op for a project without a {@code diff} config. Runs under the same per-project lock as
     * {@link #runDiff}, and surfaces a baseline config error the same way (a loud diagnostic on
     * {@code .smithy-project.json} plus a one-time notification). A transient runtime failure is
     * logged and left for the first {@code runDiff} to retry.
     *
     * @param project the project whose baseline should be pre-loaded
     */
    public void warmBaseline(Project project) {
        Optional<DiffConfig> maybeConfig = project.diffConfig();
        if (maybeConfig.isEmpty()) {
            return;
        }
        DiffConfig config = maybeConfig.get();
        String root = project.root().toString();
        DiffContext context = contextsByRoot.computeIfAbsent(root, ignored -> new DiffContext());
        context.lock.lock();
        try {
            // The context may have been evicted (project removed) between computeIfAbsent and
            // acquiring the lock; building on an orphaned context would leak a class loader, so skip.
            if (contextsByRoot.get(root) != context) {
                return;
            }
            context.ensureBaseline(project, config, providerFactory);
            // Trigger and memoize the assembly now; the result is reused by the first save's diff.
            context.provider.loadBaseline();
        } catch (BaselineModelException e) {
            // Config-loud: the baseline coordinate is unresolvable/malformed — user-fixable.
            surfaceBaselineConfigError(project, context, e);
        } catch (RuntimeException e) {
            // Runtime-quiet: an unexpected failure warming the baseline must not break startup —
            // log and leave it for the first save's runDiff to retry.
            LOGGER.log(Level.WARNING, "Skipping diff baseline warm-up: failed to load baseline", e);
        } finally {
            context.lock.unlock();
        }
    }

    // Surfaces a baseline config error (a user-fixable coordinate/resolution problem): publishes a
    // loud error diagnostic on .smithy-project.json (replacing any diff events) and notifies once.
    // Shared by the per-save diff path and the startup baseline warm-up. Returns the published
    // events so callers can compute the changed-files set.
    private List<ValidationEvent> surfaceBaselineConfigError(
            Project project, DiffContext context, BaselineModelException e) {
        String message = "Failed to load diff baseline: " + e.getMessage();
        LOGGER.log(Level.WARNING, message, e);
        List<ValidationEvent> errorEvents = List.of(baselineError(project, message));
        project.setDiffEvents(errorEvents);
        context.notifyConfigErrorOnce(message, configErrorNotifier);
        return errorEvents;
    }

    /**
     * Releases the diff resources held for a project root (closing its cached evaluator class
     * loader so its dependency jar handles aren't leaked). Call when a project is removed.
     *
     * @param root the project root (as {@code project.root().toString()})
     */
    public void evict(String root) {
        DiffContext context = contextsByRoot.remove(root);
        if (context != null) {
            context.lock.lock();
            try {
                context.reset();
            } finally {
                context.lock.unlock();
            }
        }
    }

    private static ValidationEvent baselineError(Project project, String message) {
        return ValidationEvent.builder()
                .id(BASELINE_ERROR_ID)
                .severity(Severity.ERROR)
                .message(message)
                .sourceLocation(new SourceLocation(diffConfigFilePath(project), 1, 1))
                .build();
    }

    private static String diffConfigFilePath(Project project) {
        return project.getAllBuildFilePaths().stream()
                .filter(path -> path.endsWith(".smithy-project.json"))
                .findFirst()
                .or(() -> project.getAllBuildFilePaths().stream().findFirst())
                .orElseGet(() -> project.root().toString());
    }

    /**
     * Per-project diff state: a lock serializing diff work, the baseline provider (rebuilt
     * when the coordinate or repositories change; it memoizes the assembled baseline internally),
     * and the evaluator class loader (rebuilt when
     * the resolved dependencies change). All access is under {@link #lock}.
     */
    private static final class DiffContext {
        private final ReentrantLock lock = new ReentrantLock();

        // Baseline state, keyed by the parsed baseline config + repositories.
        private DiffConfig.Baseline baseline;
        private List<MavenRepository> repositories;
        private BaselineProvider provider;

        // Evaluator class loader, keyed by the resolved dependency jar URLs.
        private List<String> classLoaderKey;
        private URLClassLoader evaluatorClassLoader;

        // Last config-error message surfaced, so we notify only when it changes.
        private String lastConfigError;

        /**
         * (Re)builds the baseline provider when the baseline config or the resolved repositories
         * differ from what's cached. Repositories are part of the key (finding #7) because a
         * Maven provider built with stale repos would resolve against the wrong place even when the
         * coordinate is unchanged.
         */
        void ensureBaseline(Project project, DiffConfig config, BaselineProviderFactory providerFactory) {
            DiffConfig.Baseline newBaseline = config.baseline();
            List<MavenRepository> newRepositories = new ArrayList<>(project.config().mavenRepositories());
            if (provider != null
                    && newBaseline.equals(baseline)
                    && newRepositories.equals(repositories)) {
                return;
            }
            this.baseline = newBaseline;
            this.repositories = newRepositories;
            this.provider = providerFactory.create(newBaseline, newRepositories);
        }

        /**
         * Returns the cached evaluator class loader, rebuilding it (and closing the old one to
         * avoid leaking dependency jar handles, finding #10) when the resolved dependencies
         * change. The loader is reused across saves rather than rebuilt + SPI-rescanned each
         * diff.
         */
        URLClassLoader evaluatorClassLoader(Project project) {
            List<URL> deps = project.config().resolvedDependencies();
            List<String> newKey = deps.stream().map(URL::toString).toList();
            if (evaluatorClassLoader != null && newKey.equals(classLoaderKey)) {
                return evaluatorClassLoader;
            }
            closeClassLoader();
            this.evaluatorClassLoader = ModelDiffRunner.evaluatorClassLoader(deps);
            this.classLoaderKey = newKey;
            return evaluatorClassLoader;
        }

        void notifyConfigErrorOnce(String message, Consumer<String> notifier) {
            if (!Objects.equals(message, lastConfigError)) {
                lastConfigError = message;
                notifier.accept(message);
            }
        }

        void clearConfigError() {
            lastConfigError = null;
        }

        /** Drops all cached state, closing the evaluator class loader. */
        void reset() {
            baseline = null;
            repositories = null;
            provider = null;
            lastConfigError = null;
            closeClassLoader();
        }

        private void closeClassLoader() {
            if (evaluatorClassLoader != null) {
                try {
                    evaluatorClassLoader.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to close evaluator class loader", e);
                }
                evaluatorClassLoader = null;
                classLoaderKey = null;
            }
        }
    }
}
