package software.amazon.smithy.lsp.util;

//import javax.print.attribute.standard.Severity;
import com.google.gson.JsonObject;
import software.amazon.smithy.lsp.SmithyLanguageClient;
import software.amazon.smithy.model.validation.Severity;

import java.util.Arrays;
import java.util.Optional;

public class ServerOptions {
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
