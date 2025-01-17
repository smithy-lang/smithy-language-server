/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Optional;
import org.eclipse.lsp4j.InitializeParams;
import software.amazon.smithy.model.validation.Severity;

public final class ServerOptions {
    private final Severity minimumSeverity;
    private final boolean onlyReloadOnSave;

    private ServerOptions(Builder builder) {
        this.minimumSeverity = builder.minimumSeverity;
        this.onlyReloadOnSave = builder.onlyReloadOnSave;
    }

    public Severity getMinimumSeverity() {
        return this.minimumSeverity;
    }

    public boolean getOnlyReloadOnSave() {
        return this.onlyReloadOnSave;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a ServerOptions instance from the initialization options provided by the client.
     * Parses and validates configuration settings from the initialization parameters.
     *
     * @param params The params passed directly from the client,
     *                             expected to be an InitializeParams object containing server configurations
     * @param client The language client used for logging configuration status and errors
     * @return A new {@code ServerOptions} instance with parsed configuration values
     **/
    public static ServerOptions fromInitializeParams(InitializeParams params, SmithyLanguageClient client) {
        // from InitializeParams
        Object initializationOptions = params.getInitializationOptions();
        Builder builder = builder();
        if (initializationOptions instanceof JsonObject jsonObject) {
            if (jsonObject.has("diagnostics.minimumSeverity")) {
                String configuredMinimumSeverity = jsonObject.get("diagnostics.minimumSeverity").getAsString();
                Optional<Severity> severity = Severity.fromString(configuredMinimumSeverity);
                if (severity.isPresent()) {
                    builder.setMinimumSeverity(severity.get());
                } else {
                    client.error(String.format("""
                            Invalid value for 'diagnostics.minimumSeverity': %s.
                            Must be one of %s.""", configuredMinimumSeverity, Arrays.toString(Severity.values())));
                }
            }
            if (jsonObject.has("onlyReloadOnSave")) {
                builder.setOnlyReloadOnSave(jsonObject.get("onlyReloadOnSave").getAsBoolean());
                client.info("Configured only reload on save: " + builder.onlyReloadOnSave);
            }
        }
        return builder.build();
    }

    protected static final class Builder {
        private Severity minimumSeverity = Severity.WARNING;
        private boolean onlyReloadOnSave = false;

        public Builder setMinimumSeverity(Severity minimumSeverity) {
            this.minimumSeverity = minimumSeverity;
            return this;
        }

        public Builder setOnlyReloadOnSave(boolean onlyReloadOnSave) {
            this.onlyReloadOnSave = onlyReloadOnSave;
            return this;
        }

        public ServerOptions build() {
            return new ServerOptions(this);
        }
    }
}
