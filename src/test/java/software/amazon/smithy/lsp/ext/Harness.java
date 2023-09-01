/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.lsp.Utils;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.ListUtils;

public class Harness implements AutoCloseable {
  private final File root;
  private final File temp;
  private final SmithyProject project;
  private final SmithyBuildExtensions config;

  private Harness(File root, File temporary, SmithyProject project, SmithyBuildExtensions config) {
    this.root = root;
    this.temp = temporary;
    this.project = project;
    this.config = config;
  }

  public File getRoot() {
    return this.root;
  }

  public SmithyProject getProject() {
    return this.project;
  }

  public File getTempFolder() {
    return this.temp;
  }

  public SmithyBuildExtensions getConfig() {
    return this.config;
  }

  public File file(String path) {
    return Paths.get(root.getAbsolutePath(), path).toFile();
  }

  public List<String> readFile(File file) throws Exception {
    return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
  }

  @Override
  public void close() {
    root.deleteOnExit();
  }

  private static Map<String, String> createFiles(List<Path> paths, File hs) {
    Map<String, String> files = new HashMap<>();
    try {
      for (Path path : paths) {
        String contents;
        String uri = path.toString();
        if (Utils.isJarFile(uri)) {
          contents = String.join(System.lineSeparator(), Utils.jarFileContents(uri));
        } else {
          contents = IoUtils.readUtf8File(path);
        }
        files.put(uri, contents);
        safeCreateFile(path.getFileName().toString(), contents, hs);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return files;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private SmithyBuildExtensions extensions = SmithyBuildExtensions.builder().build();
    private DependencyResolver dependencyResolver = new MockDependencyResolver();
    private List<Path> paths;
    private Map<String, String> files;

    private Builder() {}

    public Builder extensions(SmithyBuildExtensions extensions) {
      this.extensions = extensions;
      return this;
    }

    public Builder dependencyResolver(DependencyResolver dependencyResolver) {
      this.dependencyResolver = dependencyResolver;
      return this;
    }

    public Builder paths(Path... paths) {
      this.paths = ListUtils.of(paths);
      return this;
    }

    public Builder paths(List<Path> paths) {
      this.paths = paths;
      return this;
    }

    public Builder files(Map<String, String> files) {
      this.files = files;
      return this;
    }

    public Harness build() {
      try {
        File hs = Files.createTempDirectory("hs").toFile();
        File tmp = Files.createTempDirectory("tmp").toFile();
        if (this.files == null && this.paths != null) {
          this.files = createFiles(this.paths, hs);
        } else if (this.paths == null && this.files != null) {
          for (Entry<String, String> entry : files.entrySet()) {
            safeCreateFile(entry.getKey(), entry.getValue(), hs);
          }
        } else {
          this.files = new HashMap<>();
        }

        return loadHarness(this.extensions, hs, tmp, this.dependencyResolver);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static Harness loadHarness(SmithyBuildExtensions ext, File hs, File tmp, DependencyResolver resolver) {
    SmithyProject loaded = SmithyProject.load(ext, hs, resolver);
    if (!loaded.isBroken()) {
      return new Harness(hs, tmp, loaded, ext);
    } else {
      throw new RuntimeException(String.join("\n", loaded.getErrors()));
    }
  }

  private static void safeCreateFile(String path, String contents, File root) throws Exception {
    File f = Paths.get(root.getAbsolutePath(), path).toFile();
    new File(f.getParent()).mkdirs();
    try (FileWriter fw = new FileWriter(f)) {
      fw.write(contents);
      fw.flush();
    }
  }
}
