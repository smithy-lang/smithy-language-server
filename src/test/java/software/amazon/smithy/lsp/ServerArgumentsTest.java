package software.amazon.smithy.lsp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.cli.CliError;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerArgumentsTest {
    @Test
    void validPositionalPortNumber() {
        String[] args = {"1"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(1, serverArguments.port());
        assertFalse(serverArguments.help());
        assertTrue(serverArguments.useSocket());
    }

    @Test
    void invalidPositionalPortNumber() {
        String[] args = {"65536"};
        assertThrows(CliError.class,()-> {ServerArguments.create(args);});

    }

    @Test
    void invalidFlagPortNumber() {
        String[] args = {"-p","65536"};
        assertThrows(CliError.class,()-> {ServerArguments.create(args);});
    }

    @Test
    void validFlagPortNumberShort() {
        String[] args = {"-p","100"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(100, serverArguments.port());
        assertFalse(serverArguments.help());
        assertTrue(serverArguments.useSocket());
    }

    @Test
    void defaultPortNumber() {
        String[] args = {};
        ServerArguments serverArguments = ServerArguments.create(args);

        assertEquals(0, serverArguments.port());
        assertFalse(serverArguments.help());
        assertFalse(serverArguments.useSocket());
    }

    @Test
    void defaultPortNumberInArg() {
        String[] args = {"0"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(0, serverArguments.port());
        assertFalse(serverArguments.help());
        assertFalse(serverArguments.useSocket());
    }

    @Test
    void defaultPortNumberWithFlag() {
        String[] args = {"--port","0"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(0, serverArguments.port());
        assertFalse(serverArguments.help());
        assertFalse(serverArguments.useSocket());
    }

    @Test
    void defaultPortNumberWithShotFlag() {
        String[] args = {"-p","0"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(0, serverArguments.port());
        assertFalse(serverArguments.help());
        assertFalse(serverArguments.useSocket());
    }

    @Test
    void validFlagPortNumber() {
        String[] args = {"--port","200"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(200, serverArguments.port());
    }

    @Test
    void invalidFlag() {
        String[] args = {"--foo"};
        assertThrows(CliError.class,()-> {ServerArguments.create(args);});
    }

    @Test
    void validHelpShort() {
        String[] args = {"-h"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertTrue(serverArguments.help());
        assertFalse(serverArguments.useSocket());
    }

    @Test
    void validHelp() {
        String[] args = {"--help"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertTrue(serverArguments.help());
        assertFalse(serverArguments.useSocket());
    }
}
