/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.util.function.Consumer;
import software.amazon.smithy.cli.AnsiColorFormatter;
import software.amazon.smithy.cli.ArgumentReceiver;
import software.amazon.smithy.cli.HelpPrinter;

/**
 * Options and Params available for LSP.
 */
final class ServerArguments implements ArgumentReceiver {

    static final int MIN_PORT = 0;
    static final int MAX_PORT = 65535;
    static final int DEFAULT_PORT = 0; // Default value for unset port number.
    static final int INVALID_PORT = -1;
    static final String HELP = "--help";
    static final String HELP_SHORT = "-h";
    static final String PORT_NUMBER = "--port-number";
    static final String PORT_NUMBER_SHORT = "-p";
    static final String PORT_NUMBER_POSITIONAL = "<port_number>";
    private int portNumberInt = DEFAULT_PORT;
    private boolean help = false;
    private AnsiColorFormatter colorSetting = AnsiColorFormatter.AUTO;

    @Override
    public void registerHelp(HelpPrinter printer) {
        printer.option(HELP, HELP_SHORT, "Print this help output.");
        printer.param(PORT_NUMBER, PORT_NUMBER_SHORT, "PORT_NUMBER",
                "The port number to be used by the Smithy Language Server. Default port number is 0 if not specified.");
        printer.option(PORT_NUMBER_POSITIONAL, null, "Positional port-number.");
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
        if (name.equals(PORT_NUMBER_SHORT) || name.equals(PORT_NUMBER)) {
            return value -> {
                portNumberInt = validatePortNumber(value);
            };
        }
        return null;
    }

    public int getPortNumber() {
        return portNumberInt;
    }

    public boolean help() {
        return help;
    }

    public int validatePortNumber(String portNumberStr) {
        try {
            int portNumber = Integer.parseInt(portNumberStr);
            if (portNumber < MIN_PORT || portNumber > MAX_PORT) {
                return INVALID_PORT;
            } else {
                return portNumber;
            }
        } catch (NumberFormatException e) {
            return INVALID_PORT;
        }
    }

    public AnsiColorFormatter colorSetting() {
        return colorSetting;
    }

}
