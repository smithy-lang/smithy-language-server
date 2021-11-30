package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.io.Files;

import org.eclipse.lsp4j.jsonrpc.messages.Either;

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

  @Override
  public void close() throws Exception {
    root.deleteOnExit();
  }

  public static Harness create(SmithyBuildExtensions ext) throws Exception {
    // TODO: How to make this safe?
    File hs = Files.createTempDir();
    File tmp = Files.createTempDir();

    Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, hs);
    if (loaded.isRight())
      return new Harness(hs, tmp, loaded.getRight(), ext);
    else
      throw loaded.getLeft();
  }

  public static Harness create(SmithyBuildExtensions ext, Map<String, String> files) throws Exception {
    // TODO: How to make this safe?
    File hs = Files.createTempDir();
    File tmp = Files.createTempDir();
    for (Entry<String, String> entry : files.entrySet()) {
      safeCreateFile(entry.getKey(), entry.getValue(), hs);
    }
    Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, hs);
    if (loaded.isRight())
      return new Harness(hs, tmp, loaded.getRight(), ext);
    else
      throw loaded.getLeft();
  }

}
