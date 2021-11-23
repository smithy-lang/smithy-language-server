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
  private SmithyProject project;

  private Harness(File root, SmithyProject project) {
    this.root = root;
    this.project = project;
  }

  public File getRoot() {
    return this.root;
  }

  public SmithyProject getProject() {
    return this.project;
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
    Either<Exception, SmithyProject> loaded = this.project.recompile(f);
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
    Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, hs);
    if (loaded.isRight())
      return new Harness(hs, loaded.getRight());
    else
      throw loaded.getLeft();
  }

  public static Harness create(SmithyBuildExtensions ext, Map<String, String> files) throws Exception {
    // TODO: How to make this safe?
    File hs = Files.createTempDir();
    for (Entry<String, String> entry : files.entrySet()) {
      safeCreateFile(entry.getKey(), entry.getValue(), hs);
    }
    Either<Exception, SmithyProject> loaded = SmithyProject.load(ext, hs);
    if (loaded.isRight())
      return new Harness(hs, loaded.getRight());
    else
      throw loaded.getLeft();
  }

}
