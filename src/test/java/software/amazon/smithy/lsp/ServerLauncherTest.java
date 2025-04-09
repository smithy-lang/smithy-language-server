package software.amazon.smithy.lsp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerLauncherTest {
    private static class MockInputStream extends InputStream {
        private boolean closed = false;
        @Override
        public int read() throws IOException {
            return -1;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static class MockOutputStream extends OutputStream {
        private boolean used = false;
        private boolean closed = false;

        @Override
        public void write(int b) {
            used = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static class TestServerLauncher extends ServerLauncher {
        private final MockSocket mockSocket = new MockSocket();
        private boolean exited = false;
        TestServerLauncher(int portNumber) {
            super(portNumber);
        }

        @Override
        void handleExit() {
            exited = true;
        }

        boolean exited() {
            return exited;
        }

        @Override
        Socket createSocket() {
            return mockSocket;
        }

        Socket getMockSocket() {
            return mockSocket;
        }

    }

    private static class MockSocket extends Socket {
        private boolean isClosed = false;
        private final MockInputStream inputStream = new MockInputStream();
        private final MockOutputStream outputStream = new MockOutputStream();

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public void close() {
            isClosed = true;
        }

        public boolean isClosed() {
            return isClosed;
        }
    }

    @Test
    void testCustomPortConnection() throws IOException {
        TestServerLauncher launcher = new TestServerLauncher(8080);
        launcher.initConnection();

        assertFalse(launcher.getMockSocket().isClosed());
        assertNotNull(launcher.getMockSocket().getInputStream());
        assertNotNull(launcher.getMockSocket().getOutputStream());
    }

    @Test
    void testConnectionClose() throws IOException {
        TestServerLauncher launcher = new TestServerLauncher(8080);
        launcher.initConnection();
        launcher.closeConnection();

        assertTrue(launcher.getMockSocket().isClosed());
    }

    @Test
    void testDefaultPortConnection() throws IOException {
        ByteArrayInputStream testIn = new ByteArrayInputStream("test".getBytes());
        ByteArrayOutputStream testOut = new ByteArrayOutputStream();
        PrintStream testPrintStream = new PrintStream(testOut);

        System.setIn(testIn);
        System.setOut(testPrintStream);

        ServerLauncher launcher = new ServerLauncher(ServerArguments.DEFAULT_PORT);
        launcher.initConnection();

        assertEquals(System.in, launcher.getInputStream());
        assertEquals(System.out, launcher.getOutputStream());
        testIn.close();
        testOut.close();
    }

    @Test
    void testLaunchWithDefaultPort() throws IOException {
        TestServerLauncher launcher = new TestServerLauncher(ServerArguments.DEFAULT_PORT);
        launcher.initConnection();
        assertDoesNotThrow(launcher::launch);
        launcher.closeConnection();
        assertTrue(launcher.exited());
    }

}

