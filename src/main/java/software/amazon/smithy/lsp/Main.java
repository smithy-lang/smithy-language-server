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

/**
 * Main launcher for the Language server, started by the editor.
 */
public final class Main {
    private Main() {
    }

    /**
     * Main entry point for the language server.
     * @param args Arguments passed to the server.
     * @throws Exception If there is an error starting the server.
     */
    public static void main(String[] args) throws Exception {
        ArgumentParser parser = new ArgumentParser(args);
        parser.parse();
        if (parser.isHelp()) {
            System.exit(0);
        }
        ServerLauncher launcher = new ServerLauncher(parser.getPortNumber());
        launcher.initConnection();
        launcher.launch();
        launcher.closeConnection();
    }
}
