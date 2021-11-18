package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

import coursierapi.Dependency;
import coursierapi.Fetch;
import coursierapi.MavenRepository;
import coursierapi.Repository;
import coursierapi.error.CoursierError;

public class DependencyDownloader {
  private List<Repository> repositories;
  private List<Dependency> artifacts;
  private Fetch fetch;

  private static List<Repository> toRepositories(List<String> repos) {
    // TODO: We assume maven repos by default.
    return repos.stream().map(r -> MavenRepository.of(r)).collect(Collectors.toList());
  }

  private static List<Dependency> toDependencies(List<String> deps) throws ValidationException {
    List<Dependency> lst = new LinkedList<Dependency>();

    for (String dep : deps) {
      // TODO: we assume Maven by default
      String[] parts = dep.split(":");
      if (parts.length != 3) {
        throw new ValidationException(
            "Dependencies in smithy-build.json must have the following format: org:module:version");
      } else {
        String org = parts[0];
        String module = parts[1];
        String version = parts[2];

        lst.add(Dependency.of(org, module, version));
      }
    }

    return Collections.unmodifiableList(lst);
  }

  private DependencyDownloader(SmithyBuildExtensions extensions) throws ValidationException {
    repositories = toRepositories(extensions.getRepositories());
    artifacts = toDependencies(extensions.getArtifacts());
    fetch = Fetch.create();
    repositories.forEach(repo -> fetch.addRepositories(repo));
    artifacts.forEach(dep -> fetch.addDependencies(dep));
  }

  public static DependencyDownloader create(SmithyBuildExtensions extensions) throws ValidationException {
    return new DependencyDownloader(extensions);
  }

  private Boolean isSmithyJar(String path) {
    try {
      JarFile jar = new JarFile(new File(path));
      ZipEntry manifestEntry = jar.getEntry("META-INF/smithy/manifest");
      LspLog.println("Manifest entry in " + path + " is " + manifestEntry);
      return manifestEntry != null;
    } catch (Exception e) {
      LspLog.println("Failed to open " + path + " to check if it's a Smithy jar: " + e.toString());
      return false;
    }

  }

  private List<File> filterOutSmithyJars(List<File> jars) {
    return jars.stream().filter(file -> isSmithyJar(file.getAbsolutePath())).collect(Collectors.toList());
  }

  public List<File> download() throws CoursierError {
    // TODO: REMOVE THE SUBLIST
    return filterOutSmithyJars(fetch.fetch());
  }
}
