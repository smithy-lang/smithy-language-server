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
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import software.amazon.smithy.lsp.websocket.WebSocketRunner;

/**
 * Main launcher for the Language server, started by the editor.
 */
public final class Main {
    private Main() {
    }

    /**
     * Launch the LSP and wait for it to terminate.
     *
     * @param in  input stream for communication
     * @param out output stream for communication
     * @return Empty Optional if service terminated successfully, error otherwise
     */
    public static Optional<Exception> launch(InputStream in, OutputStream out) {
        SmithyLanguageServer server = new SmithyLanguageServer();
        Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, in, out);
        LanguageClient client = launcher.getRemoteProxy();

        server.connect(client);
        try {
            launcher.startListening().get();
            return Optional.empty();
        } catch (Exception e) {
            return Optional.of(e);
        }
    }

    /**
     * @param args Arguments passed to launch server. First argument must either be
     *             a port number for socket connection, or 0 to use STDIN and STDOUT
     *             for communication
     */
    public static void main(String[] args) {

        Socket socket = null;
        InputStream in;
        OutputStream out;
        List<String> argList = Arrays.asList(args);
        try {
            Optional<Exception> launchFailure;
            String port = getOrDefault(argList, 0, "0");
            String type = getOrDefault(argList, 1, null);

            // Check if websocket option is present
            if ("--ws".equals(type)) {
                WebSocketRunner webSocketRunner = new WebSocketRunner();
                String hostname = "localhost";
                String contextPath = "/";
                webSocketRunner.run(hostname, Integer.parseInt(port), contextPath);
                return;
            }

            // If port is set to "0", use System.in/System.out.
            if (port.equals("0")) {
                in = System.in;
                out = System.out;
            } else {
                socket = new Socket("localhost", Integer.parseInt(port));
                in = socket.getInputStream();
                out = socket.getOutputStream();
            }
            launchFailure = launch(in, out);
            if (launchFailure.isPresent()) {
                throw launchFailure.get();
            } else {
                System.out.println("Server terminated without errors");
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("Missing port argument");
        } catch (NumberFormatException e) {
            System.out.println("Port number must be a valid integer");
        } catch (Exception e) {
            System.out.println("Failed to start: " + e);

            e.printStackTrace();
        } finally {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (Exception e) {
                System.out.println("Failed to close the socket: " + e);
            }
        }
    }

    private static boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }

    private static <T> T getOrDefault(List<T> list, int index, T t) {
        if (isEmpty(list)) {
            return t;
        }
        if (index < 0 || index >= list.size()) {
            return t;
        }
        return list.get(index);
    }
}
