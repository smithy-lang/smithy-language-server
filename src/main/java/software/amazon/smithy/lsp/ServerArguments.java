/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.function.Consumer;
import software.amazon.smithy.cli.AnsiColorFormatter;
import software.amazon.smithy.cli.ArgumentReceiver;
import software.amazon.smithy.cli.Arguments;
import software.amazon.smithy.cli.CliError;
import software.amazon.smithy.cli.CliPrinter;
import software.amazon.smithy.cli.HelpPrinter;

/**
 * Options and Params available for LSP.
 */
final class ServerArguments implements ArgumentReceiver {

    private static final int MIN_PORT = 0;
    private static final int MAX_PORT = 65535;
    private static final int DEFAULT_PORT = 0; // Default value for unset port number.
    private static final String HELP = "--help";
    private static final String HELP_SHORT = "-h";
    private static final String PORT = "--port";
    private static final String PORT_SHORT = "-p";
    private static final String PORT_POSITIONAL = "<port>";
    private int port = DEFAULT_PORT;
    private boolean help = false;


    static ServerArguments create(String[] args) {
        Arguments arguments = Arguments.of(args);
        var serverArguments = new ServerArguments();
        arguments.addReceiver(serverArguments);
        var positional = arguments.getPositional();
        if (serverArguments.help()) {
            serverArguments.printHelp(arguments);
        }
        if (!positional.isEmpty()) {
            serverArguments.port = serverArguments.validatePortNumber(positional.getFirst());
        }
        return serverArguments;
    }

    @Override
    public void registerHelp(HelpPrinter printer) {
        printer.option(HELP, HELP_SHORT, "Print this help output.");
        printer.param(PORT, PORT_SHORT, "PORT",
                "The port to use for talking to the client. When not specified, or set to 0, "
                        + "standard in/out is used. Standard in/out is preferred, "
                        + "so usually this shouldn't be specified.");
        printer.option(PORT_POSITIONAL, null, "Deprecated: use --port instead. When not specified, or set to 0, "
                + "standard in/out is used. Standard in/out is preferred, so usually this shouldn't be specified.");
    }

    @Override
    public boolean testOption(String name) {
       if (name.equals(HELP) || name.equals(HELP_SHORT)) {
           help = true;
           return true;
       }
       return false;
    }

    @Override
    public Consumer<String> testParameter(String name) {
        if (name.equals(PORT_SHORT) || name.equals(PORT)) {
            return value -> {
                port = validatePortNumber(value);
            };
        }
        return null;
    }

    int port() {
        return port;
    }

    boolean help() {
        return help;
    }

    public boolean useSocket() {
        return port != 0;
    }

    private int validatePortNumber(String portStr) {
        try {
            int portNumber = Integer.parseInt(portStr);
            if (portNumber < MIN_PORT || portNumber > MAX_PORT) {
                throw new CliError("Invalid port number: should be an integer between "
                        + MIN_PORT + " and " + MAX_PORT + ", inclusive.");
            } else {
                return portNumber;
            }
        } catch (NumberFormatException e) {
            throw new CliError("Invalid port number: Can not parse " + portStr);
        }
    }

    private void printHelp(Arguments arguments) {
        CliPrinter printer = CliPrinter.fromOutputStream(System.out);
        HelpPrinter helpPrinter = HelpPrinter.fromArguments("smithy-language-server", arguments);
        helpPrinter.summary("Run the Smithy Language Server.");
        helpPrinter.print(AnsiColorFormatter.AUTO, printer);
        printer.flush();
    }

}
