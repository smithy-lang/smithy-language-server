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

//import javax.print.attribute.standard.Severity;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Optional;
import software.amazon.smithy.model.validation.Severity;

public final class ServerOptions {
    private Severity minimumSeverity = Severity.WARNING;
    private boolean onlyReloadOnSave = false;

    private ServerOptions(Builder builder) {
        this.minimumSeverity = builder.minimumSeverity;
        this.onlyReloadOnSave = builder.onlyReloadOnSave;
    }

    public Severity getMinimumSeverity() {
        return this.minimumSeverity;
    }

    public Boolean getOnlyReloadOnSave() {
        return this.onlyReloadOnSave;
    }

    public static Builder builder() {
        return new Builder();
    }

/**
 * Creates a ServerOptions instance from the initialization options provided by the client.
 * Parses and validates configuration settings from the initialization parameters.
 *
 * @param initializationOptions The raw initialization options object from the client,
 *                             expected to be a JsonObject containing server configurations
 * @param client The language client used for logging configuration status and errors
 * @return A new {@code ServerOptions} instance with parsed configuration values
 **/
    public static ServerOptions fromInitializeParams(Object initializationOptions, SmithyLanguageClient client) {
        // from InitializeParams
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

    public static class Builder {
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
