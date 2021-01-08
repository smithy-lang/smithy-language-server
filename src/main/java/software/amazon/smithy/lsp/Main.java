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
    try {
      SmithyLanguageServer server = new SmithyLanguageServer();
      Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
      LanguageClient client = launcher.getRemoteProxy();

      server.connect(client);

      launcher.startListening().get();
    } catch (Exception e) {
      System.out.println(e);

      e.printStackTrace();
    }
  }
}
