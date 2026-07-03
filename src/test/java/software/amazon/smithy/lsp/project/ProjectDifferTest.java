/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.project;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.diff.BaselineModelException;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidatedResult;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ProjectDifferTest {

    private static final String MODEL = "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n";

    @Test
    public void surfacesRemovedShapeAnchoredToNamespaceFile() {
        Project project = loadProjectWithDiffConfig();
        String sourcePath = project.getProjectFile(uri(project, "main.smithy")).path();

        // Baseline has an extra shape (example#Removed) that the current model lacks.
        Model baseline = Model.assembler()
                .addUnparsedModel("baseline.smithy",
                        "$version: \"2.0\"\nnamespace example\nstructure Foo {}\nstructure Removed {}\n")
                .assemble().unwrap();
        ProjectDiffer differ = new ProjectDiffer(
                (baselineConfig, repositories) -> () -> ValidatedResult.fromValue(baseline), message -> { });

        differ.runDiff(project);

        ValidationEvent removed = project.diffEvents().stream()
                .filter(e -> e.getId().startsWith("RemovedShape")
                             && e.getShapeId().map(id -> id.toString().equals("example#Removed")).orElse(false))
                .findFirst()
                .orElseThrow();
        // Removed shape has no current location, so it anchors to the file owning `example`.
        assertThat(removed.getSourceLocation().getFilename(), is(sourcePath));
    }

    @Test
    public void unresolvableBaselineSurfacesLoudConfigErrorAndNotifiesOnce() {
        Project project = loadProjectWithDiffConfig();
        List<String> notifications = new ArrayList<>();
        ProjectDiffer differ = new ProjectDiffer((baselineConfig, repositories) -> () -> {
            throw new BaselineModelException("could not resolve " + baselineConfig.coordinate());
        }, notifications::add);

        differ.runDiff(project);

        // Diagnostic on the config file...
        assertThat(project.diffEvents().size(), is(1));
        ValidationEvent error = project.diffEvents().get(0);
        assertThat(error.getId(), is(ProjectDiffer.BASELINE_ERROR_ID));
        assertThat(error.getSeverity(), is(Severity.ERROR));
        assertThat(error.getSourceLocation().getFilename(), endsWith(".smithy-project.json"));
        // ...plus a window notification, exactly once.
        assertThat(notifications, contains(containsString("could not resolve")));

        // A second run with the same failure must not re-notify (no popup spam).
        differ.runDiff(project);
        assertThat(notifications.size(), is(1));
    }

    @Test
    public void movingVersionPicksUpNewBaselineWithoutReload() {
        Project project = loadProjectWithCoordinate("com.example:baseline:1.0-SNAPSHOT");
        // Successive resolutions of the moving coordinate yield different baselines.
        List<Model> baselines = List.of(baselineWithExtraShape("RemovedA"), baselineWithExtraShape("RemovedB"));
        AtomicInteger call = new AtomicInteger();
        ProjectDiffer differ = new ProjectDiffer(
                (baselineConfig, repositories) -> () ->
                        ValidatedResult.fromValue(baselines.get(Math.min(call.getAndIncrement(), baselines.size() - 1))),
                message -> { });

        differ.runDiff(project);
        assertThat(removedShapeIds(project), contains("example#RemovedA"));

        // Second save re-resolves the moving baseline and reflects the new version — no reload.
        differ.runDiff(project);
        assertThat(removedShapeIds(project), contains("example#RemovedB"));
    }

    @Test
    public void urlBaselineConfigDrivesFactoryWithTheUrl() {
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        writeFile(workspace, ".smithy-project.json",
                "{ \"diff\": { \"baseline\": { \"type\": \"url\","
                        + " \"url\": \"https://example.com/baseline.json\" } } }");
        Project project = ProjectTest.load(workspace.getRoot());

        List<software.amazon.smithy.lsp.diff.DiffConfig.Baseline> baselinesSeen = new ArrayList<>();
        ProjectDiffer differ = new ProjectDiffer((baselineConfig, repositories) -> {
            baselinesSeen.add(baselineConfig);
            return () -> ValidatedResult.fromValue(baselineWithExtraShape("Removed"));
        }, message -> { });

        differ.runDiff(project);

        assertThat(baselinesSeen.size(), is(1));
        assertThat(baselinesSeen.get(0).type(), is("url"));
        assertThat(baselinesSeen.get(0).url(), is("https://example.com/baseline.json"));
        assertThat(removedShapeIds(project), contains("example#Removed"));
    }

    @Test
    public void runtimeFailureKeepsPreviousDiffEventsAndStaysQuiet() {
        Project project = loadProjectWithDiffConfig();
        // A previous successful cycle published a diff event.
        ValidationEvent previous = ValidationEvent.builder()
                .id("RemovedShape").severity(Severity.DANGER).message("previously removed").build();
        project.setDiffEvents(List.of(previous));

        List<String> notifications = new ArrayList<>();
        // An unexpected runtime failure (not a BaselineModelException config problem) must not
        // propagate out of runDiff — if it did, it would share the save-flow CompletableFuture
        // stage and suppress ordinary validation diagnostics for the saved file (finding #1).
        ProjectDiffer differ = new ProjectDiffer((baselineConfig, repositories) -> () -> {
            throw new IllegalStateException("unexpected runtime failure");
        }, notifications::add);

        differ.runDiff(project); // must not throw

        // Runtime-quiet (ADR 0007): previous diff events are kept, no config-error diagnostic,
        // and no window notification.
        assertThat(project.diffEvents(), contains(previous));
        assertThat(notifications, is(empty()));
    }

    @Test
    public void noDiffConfigClearsDiffEvents() {
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        Project project = ProjectTest.load(workspace.getRoot());
        project.setDiffEvents(List.of(ValidationEvent.builder()
                .id("RemovedShape").severity(Severity.ERROR).message("stale").build()));

        new ProjectDiffer().runDiff(project);

        assertThat(project.diffEvents(), is(empty()));
    }

    @Test
    public void reportsChangedFilesOnFirstRunAndNothingWhenUnchanged() {
        // Finding #11: runDiff reports which files' diff diagnostics may have changed so a save
        // can skip the workspace-wide refresh when the diff produced the same events as before.
        Project project = loadProjectWithDiffConfig();
        String sourcePath = project.getProjectFile(uri(project, "main.smithy")).path();
        Model baseline = baselineWithExtraShape("Removed");
        ProjectDiffer differ = new ProjectDiffer(
                (baselineConfig, repositories) -> () -> ValidatedResult.fromValue(baseline), message -> { });

        // First run surfaces a removed shape anchored to the source file: that file changed.
        Set<String> firstRun = differ.runDiff(project);
        assertThat(firstRun, contains(sourcePath));

        // Second run against the same (memoized) baseline yields identical events: nothing changed,
        // so callers take the single-file fast path instead of refreshing the whole workspace.
        Set<String> secondRun = differ.runDiff(project);
        assertThat(secondRun, is(empty()));
    }

    @Test
    public void reportsExactlyTheFilesTheDiffEventsAnchorTo() {
        // The changed set covers every file a diff event anchored to, so a save refreshes those
        // files' diagnostics even when they differ from the saved file (finding #11).
        Project project = loadProjectWithDiffConfig();
        Model baseline = baselineWithExtraShape("Removed");
        ProjectDiffer differ = new ProjectDiffer(
                (baselineConfig, repositories) -> () -> ValidatedResult.fromValue(baseline), message -> { });

        Set<String> changed = differ.runDiff(project);

        Set<String> anchoredFiles = project.diffEvents().stream()
                .map(e -> e.getSourceLocation().getFilename())
                .collect(Collectors.toSet());
        assertThat(anchoredFiles, is(not(empty())));
        assertThat(changed, is(anchoredFiles));
    }

    @Test
    public void clearingPreviousEventsReportsTheFilesThatLostDiagnostics() {
        // When the diff stops producing an event (here: no diff config), the files that previously
        // carried diff diagnostics must be refreshed so the stale diagnostics are cleared.
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        Project project = ProjectTest.load(workspace.getRoot());
        project.setDiffEvents(List.of(ValidationEvent.builder()
                .id("RemovedShape").severity(Severity.ERROR).message("stale")
                .sourceLocation(new software.amazon.smithy.model.SourceLocation("/p/a.smithy", 1, 1))
                .build()));

        Set<String> changed = new ProjectDiffer().runDiff(project);

        assertThat(changed, contains("/p/a.smithy"));
    }

    @Test
    public void reloadDropsCacheAndRebuildsProvider() {
        Project project = loadProjectWithDiffConfig();
        // A pinned coordinate is normally loaded once and held; reload must force a rebuild.
        AtomicInteger factoryCalls = new AtomicInteger();
        ProjectDiffer differ = new ProjectDiffer((baselineConfig, repositories) -> {
            factoryCalls.incrementAndGet();
            return () -> ValidatedResult.fromValue(baselineWithExtraShape("Removed"));
        }, message -> { });

        differ.runDiff(project);
        differ.runDiff(project); // pinned: reuses the cached provider, no second build
        assertThat(factoryCalls.get(), is(1));

        differ.reload(project); // reload: drops the cache, rebuilds the provider
        assertThat(factoryCalls.get(), is(2));
    }

    @Test
    public void repositoryChangeWithSameCoordinateRebuildsProvider() {
        // Same coordinate + root, different maven repositories across a project reload: the
        // provider must be rebuilt so it resolves against the new repositories (finding #7).
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        writeDiffConfig(workspace, "com.example:baseline:1.0.0");
        writeMavenRepos(workspace, "https://repo-one.example/maven");
        Project first = ProjectTest.load(workspace.getRoot());

        List<List<String>> repositoriesSeen = new ArrayList<>();
        ProjectDiffer differ = new ProjectDiffer((baselineConfig, repositories) -> {
            repositoriesSeen.add(repositories.stream().map(r -> r.getUrl()).sorted().collect(Collectors.toList()));
            return () -> ValidatedResult.fromValue(baselineWithExtraShape("Removed"));
        }, message -> { });
        differ.runDiff(first);

        // Reload the project with a different repository (coordinate unchanged).
        writeMavenRepos(workspace, "https://repo-two.example/maven");
        Project second = ProjectTest.load(workspace.getRoot());
        differ.runDiff(second);

        assertThat(repositoriesSeen.size(), is(2));
        assertThat(repositoriesSeen.get(0), is(List.of("https://repo-one.example/maven")));
        assertThat(repositoriesSeen.get(1), is(List.of("https://repo-two.example/maven")));
    }

    @Test
    public void evaluatorClassLoaderIsReusedAcrossSavesAndClosedOnEvict() throws Exception {
        // A real jar dependency (with a probe resource) so the evaluator class loader has a handle
        // whose open/closed state is observable.
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        writeProbeJarDependency(workspace);
        writeDiffConfigWithDependency(workspace, "com.example:baseline:1.0.0", "probe.jar");
        Project project = ProjectTest.load(workspace.getRoot());

        ProjectDiffer differ = new ProjectDiffer(
                (baselineConfig, repositories) -> () -> ValidatedResult.fromValue(baselineWithExtraShape("Removed")),
                message -> { });

        differ.runDiff(project);
        URLClassLoader first = capturedClassLoader(differ, project);
        differ.runDiff(project);
        URLClassLoader second = capturedClassLoader(differ, project);

        // The class loader is cached per project (finding #10): the same instance is reused across
        // saves rather than rebuilt + SPI-rescanned each diff, and it stays open while in use.
        assertThat(second, sameInstance(first));
        assertThat(first.getResource("META-INF/probe.txt"), notNullValue());

        // Eviction (project removal) closes the cached loader so dependency jar handles aren't leaked.
        differ.evict(project.root().toString());
        assertThat(first.getResource("META-INF/probe.txt"), nullValue());
    }

    @Test
    public void concurrentRunDiffDoesNotThrowAndSerializes() throws Exception {
        Project project = loadProjectWithDiffConfig();
        ProjectDiffer differ = new ProjectDiffer(
                (baselineConfig, repositories) -> () -> ValidatedResult.fromValue(baselineWithExtraShape("Removed")),
                message -> { });

        int threads = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                differ.runDiff(project);
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            future.get(); // must not throw (no concurrent-modification / class-loader-close races)
        }
        pool.shutdownNow();

        // The winning cycle's events are consistent (the removed shape from the latest model).
        assertThat(removedShapeIds(project), contains("example#Removed"));
    }

    // Reads the evaluator URLClassLoader held in the differ's private per-root DiffContext via
    // reflection (the context is intentionally private; the loader is only observable this way).
    @SuppressWarnings("unchecked")
    private static URLClassLoader capturedClassLoader(ProjectDiffer differ, Project project) throws Exception {
        var contextsField = ProjectDiffer.class.getDeclaredField("contextsByRoot");
        contextsField.setAccessible(true);
        var contexts = (java.util.Map<String, Object>) contextsField.get(differ);
        Object context = contexts.get(project.root().toString());
        var loaderField = context.getClass().getDeclaredField("evaluatorClassLoader");
        loaderField.setAccessible(true);
        return (URLClassLoader) loaderField.get(context);
    }

    private static Project loadProjectWithDiffConfig() {
        return loadProjectWithCoordinate("com.example:baseline:1.0.0");
    }

    private static Project loadProjectWithCoordinate(String coordinate) {
        TestWorkspace workspace = TestWorkspace.singleModel(MODEL);
        writeDiffConfig(workspace, coordinate);
        return ProjectTest.load(workspace.getRoot());
    }

    private static void writeDiffConfig(TestWorkspace workspace, String coordinate) {
        writeFile(workspace, ".smithy-project.json",
                "{ \"diff\": { \"baseline\": { \"type\": \"maven\", \"coordinate\": \""
                        + coordinate + "\" } } }");
    }

    private static void writeDiffConfigWithDependency(
            TestWorkspace workspace, String coordinate, String dependencyPath) {
        writeFile(workspace, ".smithy-project.json",
                "{ \"dependencies\": [ { \"name\": \"probe\", \"path\": \"" + dependencyPath + "\" } ],"
                        + " \"diff\": { \"baseline\": { \"type\": \"maven\", \"coordinate\": \""
                        + coordinate + "\" } } }");
    }

    private static void writeMavenRepos(TestWorkspace workspace, String repositoryUrl) {
        writeFile(workspace, "smithy-build.json",
                "{ \"version\": \"1.0\", \"maven\": { \"repositories\": [ { \"url\": \""
                        + repositoryUrl + "\" } ] } }");
    }

    // Writes a jar containing a single probe resource, declared as a project dependency, so the
    // evaluator class loader has a real file handle whose open/closed state is observable.
    private static void writeProbeJarDependency(TestWorkspace workspace) {
        try {
            java.nio.file.Path jar = workspace.getRoot().resolve("probe.jar");
            try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
                jos.putNextEntry(new java.util.jar.JarEntry("META-INF/probe.txt"));
                jos.write("probe".getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeFile(TestWorkspace workspace, String filename, String content) {
        try {
            Files.writeString(workspace.getRoot().resolve(filename), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> removedShapeIds(Project project) {
        return project.diffEvents().stream()
                .filter(e -> e.getId().startsWith("RemovedShape"))
                .map(e -> e.getShapeId().map(Object::toString).orElse(""))
                .collect(Collectors.toList());
    }

    private static Model baselineWithExtraShape(String shapeName) {
        return Model.assembler()
                .addUnparsedModel("baseline.smithy",
                        "$version: \"2.0\"\nnamespace example\nstructure Foo {}\nstructure " + shapeName + " {}\n")
                .assemble().unwrap();
    }

    private static String uri(Project project, String filename) {
        return project.getAllSmithyFilePaths().stream()
                .filter(path -> path.endsWith(filename))
                .map(software.amazon.smithy.lsp.protocol.LspAdapter::toUri)
                .findFirst()
                .orElseThrow();
    }
}
