package software.amazon.smithy.lsp;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.CliPrinter;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ArgumentParserTest {
    @Test
    void validPositionalPortNumber() {
        String[] args = {"1"};
        ArgumentParser parser = new ArgumentParser(args);
        parser.parse();
        assertEquals(1, parser.getPortNumber());
    }

    @Test
    void invalidPositionalPortNumber() {
        String[] args = {"65536"};
        ArgumentParser parser = new ArgumentParser(args);
        CliError error = assertThrows(CliError.class, parser::parse);
        assertEquals("Invalid port number.", error.getMessage());
    }

    @Test
    void invalidFlagPortNumber() {
        String[] args = {"-p","65536"};
        ArgumentParser parser = new ArgumentParser(args);
        CliError error = assertThrows(CliError.class, parser::parse);
        assertEquals("Invalid port number.", error.getMessage());
    }

    @Test
    void validFlagPortNumberShort() {
        String[] args = {"-p","100"};
        ArgumentParser parser = new ArgumentParser(args);
        parser.parse();
        assertEquals(100, parser.getPortNumber());
    }

    @Test
    void defaultPortNumber() {
        String[] args = {};
        ArgumentParser parser = new ArgumentParser(args);
        parser.parse();
        assertEquals(0, parser.getPortNumber());
    }

    @Test
    void defaultPortNumberInArg() {
        String[] args = {"0"};
        ArgumentParser parser = new ArgumentParser(args);
        parser.parse();
        assertEquals(0, parser.getPortNumber());
    }

    @Test
    void validFlagPortNumber() {
        String[] args = {"--port-number","200"};
        ArgumentParser parser = new ArgumentParser(args);
        parser.parse();
        assertEquals(200, parser.getPortNumber());
    }

    @Test
    void validHelp() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CliPrinter testPrinter = CliPrinter.fromOutputStream(outputStream);
        String[] args = {"--help"};

        ArgumentParser parser = new ArgumentParser(args, testPrinter);

        parser.parse();

        String output = outputStream.toString();
        assertTrue(output.contains("Usage: java -jar smithy-lsp.jar [--help | -h] "));
        assertTrue(output.contains("[--port-number | -p PORT_NUMBER] "));
    }

    @Test
    void invalidFlag() {
        String[] args = {"--foo"};
        ArgumentParser parser = new ArgumentParser(args);
        CliError error = assertThrows(CliError.class, parser::parse);
        assertEquals("Unexpected CLI argument: --foo", error.getMessage());
    }
}
