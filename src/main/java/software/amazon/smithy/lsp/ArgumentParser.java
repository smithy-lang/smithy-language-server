/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.List;
import software.amazon.smithy.cli.Arguments;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.CliPrinter;
import software.amazon.smithy.cli.HelpPrinter;

final class ArgumentParser {
    private static final int PORT_NUMBER_INDEX = 0;
    private final ServerArguments argumentReceiver = new ServerArguments();
    private final Arguments arguments;
    private final CliPrinter cliPrinter;
    private List<String> positional;
    private int portNumber;

    ArgumentParser(String[] args) {
        this(args, CliPrinter.fromOutputStream(System.out));
    }

    ArgumentParser(String[] args, CliPrinter cliPrinter) {
        arguments = Arguments.of(args);
        arguments.addReceiver(argumentReceiver);
        this.cliPrinter = cliPrinter;
    }

    private void printHelp() {
        HelpPrinter helpPrinter = HelpPrinter.fromArguments("java -jar smithy-lsp.jar", arguments);
        helpPrinter.summary("Options for the Smithy Language Server:");
        helpPrinter.print(argumentReceiver.colorSetting(), cliPrinter);
        cliPrinter.flush();
    }

    /**
     * Parse the arguments received.
     *
     */
    public void parse() {
        positional = arguments.getPositional();
        if (argumentReceiver.help()) {
            printHelp();
        } else {
            parsePortNumber();
        }
    }

    /**
     * Check if the help flag was passed.
     *
     * @return True if the help flag was passed, false otherwise.
     */
    public boolean isHelp() {
        return argumentReceiver.help();
    }

    /**
     * Parse the port number from the arguments.
     *
     */
    public void parsePortNumber() {
        portNumber = argumentReceiver.getPortNumber();
        if (!positional.isEmpty() && portNumber == ServerArguments.DEFAULT_PORT) {
            portNumber = argumentReceiver.validatePortNumber(positional.get(PORT_NUMBER_INDEX));
        }
        if (portNumber == ServerArguments.INVALID_PORT) {
            throw new CliError("Invalid port number.");
        }
    }

    /**
     * Get the port number based on the positional and flag arguments.
     * @return The port number of the Smithy Language Server.
     */
    public int getPortNumber() {
        return portNumber;
    }
}
