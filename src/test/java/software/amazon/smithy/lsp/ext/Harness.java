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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.FileCacheResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
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

  private static File safeCreateFile(String path, String contents, File root) throws Exception {
    File f = Paths.get(root.getAbsolutePath(), path).toFile();
    new File(f.getParent()).mkdirs();
    try (FileWriter fw = new FileWriter(f)) {
      fw.write(contents);
      fw.flush();
    }

    return f;
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

  public static Harness create(SmithyBuildExtensions ext) throws Exception {
    File hs = Files.createTempDirectory("hs").toFile();
    File tmp = Files.createTempDirectory("tmp").toFile();
    return loadHarness(ext, hs, tmp, new MockDependencyResolver(ListUtils.of()));
  }

  public static Harness create(SmithyBuildExtensions ext, Map<String, String> files) throws Exception {
    File hs = Files.createTempDirectory("hs").toFile();
    File tmp = Files.createTempDirectory("tmp").toFile();
    for (Entry<String, String> entry : files.entrySet()) {
      safeCreateFile(entry.getKey(), entry.getValue(), hs);
    }
    return loadHarness(ext, hs, tmp, new MockDependencyResolver(ListUtils.of()));
  }

  public static Harness create(SmithyBuildExtensions ext, List<Path> files) throws Exception {
    File hs = Files.createTempDirectory("hs").toFile();
    File tmp = Files.createTempDirectory("tmp").toFile();
    for (Path path : files) {
      if (Utils.isJarFile(path.toString())) {
        String contents = String.join(System.lineSeparator(), Utils.jarFileContents(path.toString()));
        safeCreateFile(path.getFileName().toString(), contents, hs);
      } else {
        safeCreateFile(path.getFileName().toString(), IoUtils.readUtf8File(path), hs);
      }
    }
    return loadHarness(ext, hs, tmp, new MockDependencyResolver(ListUtils.of()));
  }

  public static Harness create(SmithyBuildExtensions ext, DependencyResolver resolver) throws Exception {
    File hs = Files.createTempDirectory("hs").toFile();
    File tmp = Files.createTempDirectory("tmp").toFile();
    return loadHarness(ext, hs, tmp, resolver);
  }

  private static Harness loadHarness(SmithyBuildExtensions ext, File hs, File tmp, DependencyResolver resolver) throws Exception {
    Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, hs, resolver);
    if (loaded.isRight())
      return new Harness(hs, tmp, loaded.getRight(), ext);
    else
      throw loaded.getLeft();
  }
}
