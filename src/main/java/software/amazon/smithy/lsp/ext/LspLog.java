package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This log interface buffers the messages until the server receives information
 * about workspace root.
 * 
 * This helps placing the log messages into a file in the workspace, rather than
 * pre-defined location on filesystem.
 */
public class LspLog {
  private static FileWriter fw = null;
  private static Optional<List<Object>> buffer = Optional.of(new ArrayList<Object>());

  private LspLog() {
  }

  public static void setWorkspaceFolder(String folder) {
    try {
      fw = new FileWriter(new File(folder + "/.smithy.lsp.log"));
      synchronized (buffer) {
        buffer.ifPresent(buf -> buf.forEach(line -> println(line)));
        buffer = Optional.empty();
      }
    } catch (IOException e) {
      // TODO: handle exception
    }

  }

  public static void println(Object a) {
    try {
      if (fw != null)
        fw.append(a.toString() + "\n").flush();
      else {
        synchronized (buffer) {
          buffer.ifPresent(buf -> buf.add(a));
        }
      }
    } catch (Exception e) {

    }
  }
}
