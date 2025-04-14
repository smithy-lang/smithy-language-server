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
import org.eclipse.lsp4j.launch.LSPLauncher;
import software.amazon.smithy.cli.AnsiColorFormatter;
import software.amazon.smithy.cli.CliPrinter;
import software.amazon.smithy.cli.HelpPrinter;

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
        var serverArguments = ServerArguments.create(args);
        if (serverArguments.help()) {
            printHelp(serverArguments);
            System.exit(0);
        }

        launch(serverArguments);
    }

    private static void launch(ServerArguments serverArguments) throws Exception {
        if (serverArguments.useSocket()) {
            try (var socket = new Socket("localhost", serverArguments.port())) {
                startServer(socket.getInputStream(), socket.getOutputStream());
            }
        } else {
            startServer(System.in, System.out);
        }
    }

    private static void startServer(InputStream in, OutputStream out) throws Exception {
        var server = new SmithyLanguageServer();
        var launcher = LSPLauncher.createServerLauncher(server, in, out);

        var client = launcher.getRemoteProxy();
        server.connect(client);

        launcher.startListening().get();
    }

    private static void printHelp(ServerArguments serverArguments) {
        CliPrinter printer = CliPrinter.fromOutputStream(System.out);
        HelpPrinter helpPrinter = new HelpPrinter("smithy-language-server");
        serverArguments.registerHelp(helpPrinter);
        helpPrinter.summary("Run the Smithy Language Server.");
        helpPrinter.print(AnsiColorFormatter.AUTO, printer);
        printer.flush();
    }
}
