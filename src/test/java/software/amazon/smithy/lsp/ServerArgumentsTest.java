package software.amazon.smithy.lsp;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.cli.CliError;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerArgumentsTest {
    @Test
    void validPositionalPortNumber() {
        String[] args = {"1"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(1, serverArguments.getPortNumber());
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
        assertEquals(100, serverArguments.getPortNumber());
    }

    @Test
    void defaultPortNumber() {
        String[] args = {};
        ServerArguments serverArguments = ServerArguments.create(args);

        assertEquals(0, serverArguments.getPortNumber());
    }

    @Test
    void defaultPortNumberInArg() {
        String[] args = {"0"};
        ServerArguments serverArguments = ServerArguments.create(args);

        assertEquals(0, serverArguments.getPortNumber());
    }

    @Test
    void validFlagPortNumber() {
        String[] args = {"--port-number","200"};
        ServerArguments serverArguments = ServerArguments.create(args);
        assertEquals(200, serverArguments.getPortNumber());
    }

    @Test
    void validHelp() {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outContent));

        try {
            ServerArguments.create(new String[]{"--help"});

            String output = outContent.toString().trim();

            assertTrue(output.contains("Options for the Smithy Language Server:"));
            assertTrue(output.contains("--help"));
            assertTrue(output.contains("-h"));
            assertTrue(output.contains("Print this help output."));
            assertTrue(output.contains("--port-number"));
            assertTrue(output.contains("-p"));
            assertTrue(output.contains("PORT_NUMBER"));
            assertTrue(output.contains("The port number to be used by the Smithy Language Server."));
            assertTrue(output.contains("<port_number>"));
            assertTrue(output.contains("Positional port-number."));

        } finally {
            // Restore original System.out
            System.setOut(originalOut);
        }
    }

    @Test
    void invalidFlag() {
        String[] args = {"--foo"};
        assertThrows(CliError.class,()-> {ServerArguments.create(args);});
    }
}
