/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.validation.ValidatedResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MavenBaselineProviderTest {

    private static final String BASELINE_IDL = String.join("\n",
            "$version: \"2.0\"",
            "namespace example",
            "structure Baseline { id: String }");

    @Test
    public void assemblesModelFromJarManifest(@TempDir Path tempDir) throws IOException {
        Path jar = buildModelJar(tempDir, BASELINE_IDL);

        ValidatedResult<Model> result = MavenBaselineProvider.assembleFrom(List.of(jar));

        Model model = result.getResult().orElseThrow();
        assertThat(model.expectShape(ShapeId.from("example#Baseline")).getId().toString(),
                is("example#Baseline"));
    }

    @Test
    public void loadBaselineResolvesThenAssembles(@TempDir Path tempDir) throws IOException {
        Path jar = buildModelJar(tempDir, BASELINE_IDL);
        DependencyResolver resolver = new FakeResolver(
                List.of(ResolvedArtifact.fromCoordinates(jar, "example:baseline:1.0.0")));

        MavenBaselineProvider provider =
                new MavenBaselineProvider("example:baseline:1.0.0", List.of(), false, () -> resolver);
        ValidatedResult<Model> result = provider.loadBaseline();

        assertThat(result.getResult().isPresent(), is(true));
        assertThat(result.getResult().get().expectShape(ShapeId.from("example#Baseline")).getId().toString(),
                is("example#Baseline"));
    }

    @Test
    public void loadsOnlyPrimaryArtifactNotTransitiveDependencies(@TempDir Path tempDir) throws IOException {
        // The baseline is self-contained; a transitive dep jar redefines the same shape.
        Path primary = buildModelJar(tempDir, "primary.jar",
                "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n");
        Path dep = buildModelJar(tempDir, "dep.jar",
                "$version: \"2.0\"\nnamespace example\nstructure Foo {}\nstructure DepExtra {}\n");
        DependencyResolver resolver = new FakeResolver(List.of(
                ResolvedArtifact.fromCoordinates(primary, "example:baseline:1.0.0"),
                ResolvedArtifact.fromCoordinates(dep, "other:dep:1.0.0")));

        MavenBaselineProvider provider =
                new MavenBaselineProvider("example:baseline:1.0.0", List.of(), false, () -> resolver);
        Model model = provider.loadBaseline().getResult().orElseThrow();

        // Only the primary artifact is loaded: its shape is present, the dep's is not, and there
        // is no duplicate-shape conflict (loading both would redefine example#Foo).
        assertThat(model.getShape(ShapeId.from("example#Foo")).isPresent(), is(true));
        assertThat(model.getShape(ShapeId.from("example#DepExtra")).isPresent(), is(false));
    }

    @Test
    public void loadsTransitiveDependenciesWhenConfigured(@TempDir Path tempDir) throws IOException {
        // A non-self-contained baseline: the primary artifact's shape lives in one jar, a trait
        // definition it relies on lives in a transitive dependency jar (no overlapping shapes).
        Path primary = buildModelJar(tempDir, "primary.jar",
                "$version: \"2.0\"\nnamespace example\nstructure Foo {}\n");
        Path dep = buildModelJar(tempDir, "dep.jar",
                "$version: \"2.0\"\nnamespace example\nstructure DepExtra {}\n");
        DependencyResolver resolver = new FakeResolver(List.of(
                ResolvedArtifact.fromCoordinates(primary, "example:baseline:1.0.0"),
                ResolvedArtifact.fromCoordinates(dep, "other:dep:1.0.0")));

        MavenBaselineProvider provider =
                new MavenBaselineProvider("example:baseline:1.0.0", List.of(), true, () -> resolver);
        Model model = provider.loadBaseline().getResult().orElseThrow();

        // Both the primary artifact and its transitive dependency are loaded.
        assertThat(model.getShape(ShapeId.from("example#Foo")).isPresent(), is(true));
        assertThat(model.getShape(ShapeId.from("example#DepExtra")).isPresent(), is(true));
    }

    @Test
    public void throwsWhenResolutionFails() {
        DependencyResolver resolver = new FakeResolver(() -> {
            throw new RuntimeException("network down");
        });
        MavenBaselineProvider provider =
                new MavenBaselineProvider("example:baseline:1.0.0", List.of(), false, () -> resolver);

        assertThrows(BaselineModelException.class, provider::loadBaseline);
    }

    @Test
    public void throwsWhenNoArtifactsResolved() {
        DependencyResolver resolver = new FakeResolver(List.of());
        MavenBaselineProvider provider =
                new MavenBaselineProvider("example:baseline:1.0.0", List.of(), false, () -> resolver);

        assertThrows(BaselineModelException.class, provider::loadBaseline);
    }


    /** Builds a jar containing a Smithy model behind a standard {@code META-INF/smithy/manifest}. */
    private static Path buildModelJar(Path tempDir, String idl) throws IOException {
        return buildModelJar(tempDir, "baseline.jar", idl);
    }

    private static Path buildModelJar(Path tempDir, String jarName, String idl) throws IOException {
        Path jar = tempDir.resolve(jarName);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            write(jos, "META-INF/smithy/manifest", "baseline.smithy\n");
            write(jos, "META-INF/smithy/baseline.smithy", idl);
        }
        return jar;
    }

    private static void write(JarOutputStream jos, String name, String content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }

    /** Minimal {@link DependencyResolver} that returns canned artifacts (or throws on resolve). */
    private static final class FakeResolver implements DependencyResolver {
        private final List<ResolvedArtifact> artifacts;
        private final Runnable onResolve;

        FakeResolver(List<ResolvedArtifact> artifacts) {
            this.artifacts = artifacts;
            this.onResolve = null;
        }

        FakeResolver(Runnable onResolve) {
            this.artifacts = List.of();
            this.onResolve = onResolve;
        }

        @Override
        public void addRepository(MavenRepository repository) {
        }

        @Override
        public void addDependency(String coordinates) {
        }

        @Override
        public List<ResolvedArtifact> resolve() {
            if (onResolve != null) {
                onResolve.run();
            }
            return artifacts;
        }
    }
}
