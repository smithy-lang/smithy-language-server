package software.amazon.smithy.lsp.ext;

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;

public class MockDependencyResolver implements DependencyResolver {
    final List<ResolvedArtifact> artifacts;
    final List<MavenRepository> repositories = new ArrayList<>();
    final List<String> coordinates = new ArrayList<>();

    MockDependencyResolver(List<ResolvedArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    @Override
    public void addRepository(MavenRepository repository) {
        repositories.add(repository);
    }

    @Override
    public void addDependency(String coordinates) {
        this.coordinates.add(coordinates);
    }

    @Override
    public List<ResolvedArtifact> resolve() {
        return artifacts;
    }
}
