/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

class ServerLauncher {
    private static final String SOCKET_HOST = "localhost";
    private static final Logger LOGGER = Logger.getLogger(ServerLauncher.class.getName());
    private final int portNumber;
    private InputStream in;
    private OutputStream out;
    private Socket socket;

    ServerLauncher(int portNumber) {
        this.portNumber = portNumber;
    }

    Socket createSocket() throws IOException {
        return new Socket(SOCKET_HOST, portNumber);
    }

    void initConnection() throws IOException {
        if (portNumber == ServerArguments.DEFAULT_PORT) {
            in = System.in;
            out = System.out;
        } else {
            socket = createSocket();
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }
    }

    InputStream getInputStream() {
        return in;
    }

    OutputStream getOutputStream() {
        return out;
    }

    void closeConnection() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            LOGGER.severe("Failed to close the socket");
        }
    }

    void handleExit() {
        closeConnection();
        System.exit(0);
    }

    private InputStream exitOnClose(InputStream delegate) {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                int result = delegate.read();
                if (result < 0) {
                    handleExit();
                }
                return result;
            }
        };
    }

    /**
     * Launch the LSP and wait for it to terminate.
     *
     */
    void launch() throws InterruptedException, ExecutionException {
        SmithyLanguageServer server = new SmithyLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(
                server,
                exitOnClose(in),
                out);
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        launcher.startListening().get();
    }
}
