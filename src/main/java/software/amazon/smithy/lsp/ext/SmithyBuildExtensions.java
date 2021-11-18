package software.amazon.smithy.lsp.ext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

public final class SmithyBuildExtensions implements ToSmithyBuilder<SmithyBuildExtensions> {
  private final List<String> mavenRepositories;
  private final List<String> mavenDependencies;

  private SmithyBuildExtensions(Builder b) {
    this.mavenDependencies = ListUtils.copyOf(b.mavenDependencies);
    this.mavenRepositories = ListUtils.copyOf(b.mavenRepositories);
  }

  public List<String> getMavenDependencies() {
    return mavenDependencies;
  }

  public List<String> getMavenRepositories() {
    return mavenRepositories;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public SmithyBuilder<SmithyBuildExtensions> toBuilder() {
    return builder().mavenDependencies(mavenDependencies).mavenRepositories(mavenRepositories);
  }

  public static final class Builder implements SmithyBuilder<SmithyBuildExtensions> {
    private final List<String> mavenRepositories = new ArrayList<>();
    private final List<String> mavenDependencies = new ArrayList<>();

    @Override
    public SmithyBuildExtensions build() {
      return new SmithyBuildExtensions(this);
    }

    public Builder merge(SmithyBuildExtensions other) {
      mavenDependencies.addAll(other.mavenDependencies);
      mavenRepositories.addAll(other.mavenRepositories);

      return this;
    }

    public Builder mavenRepositories(Collection<String> mavenRepositories) {
      this.mavenRepositories.clear();
      this.mavenRepositories.addAll(mavenRepositories);
      return this;
    }

    public Builder mavenDependencies(Collection<String> mavenDependencies) {
      this.mavenDependencies.clear();
      this.mavenDependencies.addAll(mavenDependencies);
      return this;
    }
  }

  @Override
  public String toString() {
    return "SmithyBuildExtensions(repositories=" + mavenRepositories.toString() + ",artifacts="
        + mavenDependencies.toString() + ")";
  }
}
