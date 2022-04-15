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

import com.google.common.io.Files;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.utils.IoUtils;

public class Harness implements AutoCloseable {
  private File root;
  private File temp;
  private SmithyProject project;
  private SmithyBuildExtensions config;

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

  public File addFile(String path, String contents) throws Exception {
    File f = safeCreateFile(path, contents, this.root);
    Either<Exception, SmithyProject> loaded = this.project.recompile(f, null);
    if (loaded.isRight())
      this.project = loaded.getRight();
    else
      throw loaded.getLeft();

    return f;
  }

  public File file(String path) throws Exception {
    return Paths.get(root.getAbsolutePath(), path).toFile();
  }

  public List<String> readFile(File file) throws Exception {
    return Files.readLines(file, Charset.forName("UTF-8"));
  }

  @Override
  public void close() throws Exception {
    root.deleteOnExit();
  }

  public static Harness create(SmithyBuildExtensions ext) throws Exception {
    // TODO: How to make this safe?
    File hs = Files.createTempDir();
    File tmp = Files.createTempDir();
    return loadHarness(ext, hs, tmp);
  }

  public static Harness create(SmithyBuildExtensions ext, Map<String, String> files) throws Exception {
    // TODO: How to make this safe?
    File hs = Files.createTempDir();
    File tmp = Files.createTempDir();
    for (Entry<String, String> entry : files.entrySet()) {
      safeCreateFile(entry.getKey(), entry.getValue(), hs);
    }
    return loadHarness(ext, hs, tmp);
  }

  public static Harness create(SmithyBuildExtensions ext, List<Path> files) throws Exception {
    File hs = Files.createTempDir();
    File tmp = Files.createTempDir();
    for (Path path : files) {
      safeCreateFile(path.getFileName().toString(), IoUtils.readUtf8File(path), hs);
    }
    return loadHarness(ext, hs, tmp);
  }

  private static Harness loadHarness(SmithyBuildExtensions ext, File hs, File tmp) throws Exception {
    Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, hs);
    if (loaded.isRight())
      return new Harness(hs, tmp, loaded.getRight(), ext);
    else
      throw loaded.getLeft();
  }
}
