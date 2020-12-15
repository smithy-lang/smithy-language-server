/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;


public class Main {
  public String getGreeting() {
    return "Hello world.";
  }

  /**
   * @param args Arguments passed to launch server.
   */
  public static void main(String[] args) {

    Socket socket = null;

    try {
      String port = args[0];
      socket = new Socket("localhost", Integer.parseInt(port));

      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();
      SmithyLanguageServer server = new SmithyLanguageServer();
      Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
      LanguageClient client = launcher.getRemoteProxy();

      server.connect(client);

      launcher.startListening().get();
    } catch (ArrayIndexOutOfBoundsException e) {
      System.out.println("Missing port argument");
    } catch (NumberFormatException e) {
      System.out.println("Port number must be a valid integer");
    } catch (Exception e) {
      System.out.println(e);

      e.printStackTrace();
    } finally {
      try {
        if (socket != null) {
          socket.close();
        }
      } catch (Exception e) {
        System.out.println("Failed to close the socket");
        System.out.println(e);
      }
    }

  }
}
