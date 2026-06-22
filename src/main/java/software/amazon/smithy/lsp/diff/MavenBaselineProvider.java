/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.EnvironmentVariable;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.MavenDependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.loader.ModelDiscovery;
import software.amazon.smithy.model.validation.ValidatedResult;

/**
 * Baseline provider that resolves a Maven coordinate (e.g.
 * {@code com.disneystreaming.api.registry:registry-snapshot:1.2.3}) with the same
 * {@link DependencyResolver} machinery the language server already uses, then assembles the
 * baseline model from the resolved jar(s).
 *
 * <p>Assembly mirrors {@code api-registry-cli}'s {@code ModelLoader.withoutCurrentClassloader}:
 * each resolved jar is opened as a zip {@link FileSystem} (without a class loader) and its
 * {@code META-INF/smithy/manifest} is read directly, so the baseline cannot be contaminated by
 * Smithy models that happen to be on the application classpath. Validation is disabled — the
 * baseline was validated when it was published, and only diff events are ultimately surfaced.
 */
public final class MavenBaselineProvider implements BaselineProvider {

    private final String coordinate;
    private final List<MavenRepository> repositories;
    private final boolean transitiveDependencies;
    private final Supplier<DependencyResolver> resolverFactory;

    // Memoizes the assembled baseline so it is resolved and assembled once per provider instance
    // and reused across saves. The provider is rebuilt (and this cache dropped) when the
    // configured coordinate changes or on an explicit reload.
    private ValidatedResult<Model> memoizedResult;

    /**
     * @param coordinate the Maven coordinate of the baseline artifact
     * @param repositories repositories to resolve from
     * @param transitiveDependencies whether to also load the coordinate's transitive dependency jars
     *  (see {@link #resolveJars})
     * @param resolverFactory supplies a fresh {@link DependencyResolver} per resolution
     */
    public MavenBaselineProvider(
            String coordinate,
            List<MavenRepository> repositories,
            boolean transitiveDependencies,
            Supplier<DependencyResolver> resolverFactory
    ) {
        this.coordinate = Objects.requireNonNull(coordinate, "coordinate");
        this.repositories = List.copyOf(repositories);
        this.transitiveDependencies = transitiveDependencies;
        this.resolverFactory = Objects.requireNonNull(resolverFactory, "resolverFactory");
    }

    /**
     * Creates a provider using the default Maven resolver (same as the server's project
     * loading: a {@link MavenDependencyResolver} backed by the Smithy Maven cache).
     */
    public MavenBaselineProvider(
            String coordinate,
            List<MavenRepository> repositories,
            boolean transitiveDependencies
    ) {
        this(coordinate, repositories, transitiveDependencies,
                () -> new MavenDependencyResolver(EnvironmentVariable.SMITHY_MAVEN_CACHE.get()));
    }

    @Override
    public synchronized ValidatedResult<Model> loadBaseline() {
        if (memoizedResult == null) {
          List<Path> jars = resolveJars();
          memoizedResult = assembleFrom(jars);
        }

        return memoizedResult;
    }

    private List<Path> resolveJars() {
        try {
            DependencyResolver resolver = resolverFactory.get();
            for (MavenRepository repository : repositories) {
                resolver.addRepository(repository);
            }
            resolver.addDependency(coordinate);
            List<ResolvedArtifact> artifacts = resolver.resolve();
            // By default load ONLY the requested artifact, not its transitive dependencies. The
            // baseline is then expected to be a self-contained model (e.g. a flattened JSON
            // serialization that already includes trait definitions from its dependencies); also
            // loading those dependency jars would define the same shapes twice ("Duplicate shape").
            //
            // When transitiveDependencies is enabled, every resolved jar is loaded instead, so a
            // baseline that is NOT self-contained can pull its trait definitions from its
            // dependencies. The caller opts into this knowing the artifact won't redefine shapes
            // its dependencies already declare.
            String groupArtifact = groupArtifactOf(coordinate);
            List<Path> jars = new ArrayList<>();
            for (ResolvedArtifact artifact : artifacts) {
                boolean isPrimaryArtifact =
                        (artifact.getGroupId() + ":" + artifact.getArtifactId()).equals(groupArtifact);
                if (transitiveDependencies || isPrimaryArtifact) {
                    Path path = artifact.getPath().toAbsolutePath();
                    // Mirror ProjectConfigLoader's safeguard: the resolver can report an artifact
                    // that isn't actually on disk, which would otherwise surface later as an
                    // opaque jar-read failure during assembly.
                    if (!Files.exists(path)) {
                        throw new BaselineModelException(String.format(
                                "Baseline artifact %s was resolved but not found on disk at %s", artifact, path));
                    }
                    jars.add(path);
                }
            }
            if (jars.isEmpty()) {
                throw new BaselineModelException(
                        "Baseline coordinate '" + coordinate + "' did not resolve to a matching artifact");
            }
            return jars;
        } catch (BaselineModelException e) {
            // Already a baseline error with a specific, user-facing message (e.g. "not found on
            // disk", "did not resolve to a matching artifact"); surface it as-is rather than
            // demoting it to the cause of the generic message below.
            throw e;
        } catch (RuntimeException e) {
            throw new BaselineModelException(
                    "Failed to resolve baseline coordinate '" + coordinate + "'", e);
        }
    }

    // The "group:artifact" of a Maven coordinate (which may be group:artifact:version or include
    // a packaging/classifier). The version is always the last segment, so the first two are the
    // group and artifact.
    private static String groupArtifactOf(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 2) {
            throw new BaselineModelException("Malformed Maven coordinate '" + coordinate + "'");
        }
        return parts[0] + ":" + parts[1];
    }

    /**
     * Assembles a model from the given jars by reading each jar's Smithy manifest.
     * Package-private for testing.
     */
    static ValidatedResult<Model> assembleFrom(List<Path> jars) {
        ModelAssembler assembler = Model.assembler()
                .putProperty(ModelAssembler.ALLOW_UNKNOWN_TRAITS, true)
                .disableValidation();

        List<URL> urls = new ArrayList<>();
        try {
            for (Path jar: jars) {
                urls.add(jar.toUri().toURL());
            }
        } catch (MalformedURLException e) {
            throw new BaselineModelException("Bad baseline jar path", e);
        }

        // Discover the models through a class loader scoped to ONLY the baseline jars (null
        // parent). This reads `jar:` URLs via the standard jar URL handler rather than the zip
        // FileSystem provider (jdk.zipfs), which may be absent from a jlink runtime image, and
        // the null parent keeps it from pulling in models on the application classpath.
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[urls.size()]), null)) {
            List<URL> modelUrls = ModelDiscovery.findModels(loader);
            // A coordinate that resolves but carries no Smithy models (no META-INF/smithy/manifest)
            // is a user-fixable misconfiguration — the coordinate points at a non-Smithy artifact.
            // Surface it loudly as a baseline error rather than silently diffing against an empty
            // model (which would report every shape as added).
            if (modelUrls.isEmpty()) {
                throw new BaselineModelException(
                        "Baseline jars " + jars + " contain no Smithy models; the coordinate may "
                        + "point at a non-Smithy artifact");
            }
            for (URL modelUrl : modelUrls) {
                assembler.addImport(modelUrl);
            }
        } catch (IOException e) {
            throw new BaselineModelException("Failed to read baseline jars " + jars, e);
        }

        return assembler.assemble();
    }
}
