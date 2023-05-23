/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.websocket;

import jakarta.websocket.DeploymentException;
import org.glassfish.tyrus.server.Server;
import software.amazon.smithy.lsp.ext.LspLog;

public class WebSocketRunner {
    private static final String DEFAULT_HOSTNAME = "localhost";
    private static final int DEFAULT_PORT = 3000;
    private static final String DEFAULT_CONTEXT_PATH = "/";

    /**
     * Run the websocket server on port of given host and path.
     * @param hostname hostname for server
     * @param port port server will listen on
     * @param contextPath path which routes to the lsp
     */
    public void run(String hostname, int port, String contextPath) {
        Server server = new Server(
                hostname != null ? hostname : DEFAULT_HOSTNAME,
                port > 0 ? port : DEFAULT_PORT,
                contextPath != null ? contextPath : DEFAULT_CONTEXT_PATH,
                null,
                SmithyWebSocketServerConfigProvider.class
        );
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "smithy-lsp-websocket-server-shutdown-hook"));

        try {
            server.start();
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            LspLog.println("Smithy LSP Websocket server has been interrupted.");
            Thread.currentThread().interrupt();
        } catch (DeploymentException e) {
            LspLog.println("Could not start Smithy LSP Websocket server.");
        } finally {
            server.stop();
        }
    }
}
