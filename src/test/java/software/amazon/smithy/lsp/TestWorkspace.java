/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;

/**
 * Sets up a temporary directory containing a Smithy project
 */
public class TestWorkspace {
    private static final NodeMapper MAPPER = new NodeMapper();
    private final Path root;

    private TestWorkspace(Path root) {
        this.root = root;
    }

    /**
     * @return The path of the workspace root
     */
    public Path getRoot() {
        return root;
    }

    /**
     * @param filename The name of the file to get the URI for. Can be relative to the root
     * @return The LSP URI for the given filename
     */
    public String getUri(String filename) {
        return this.root.resolve(filename).toUri().toString();
    }

    /**
     * @param model String of the model to create in the workspace
     * @return A workspace with a single model, "main.smithy", with the given contents, and
     *  a smithy-build.json with sources = ["main.smithy"]
     */
    public static TestWorkspace singleModel(String model) {
        return builder()
                .withSourceFile("main.smithy", model)
                .build();
    }

    /**
     * @param models Strings of the models to create in the workspace
     * @return A workspace with n models, each "model-n.smithy", with their given contents,
     *  and a smithy-build.json with sources = [..."model-n.smithy"]
     */
    public static TestWorkspace multipleModels(String... models) {
        Builder builder = builder();
        for (int i = 0; i < models.length; i++) {
            builder.withSourceFile("model-" + i + ".smithy", models[i]);
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Dir {
        String path;
        Map<String, String> sourceModels = new HashMap<>();
        Map<String, String> importModels = new HashMap<>();
        List<Dir> sourceDirs = new ArrayList<>();
        List<Dir> importDirs = new ArrayList<>();

        public Dir path(String path) {
            this.path = path;
            return this;
        }

        public Dir withSourceFile(String filename, String model) {
            this.sourceModels.put(filename, model);
            return this;
        }

        public Dir withImportFile(String filename, String model) {
            this.importModels.put(filename, model);
            return this;
        }

        public Dir withSourceDir(Dir dir) {
            this.sourceDirs.add(dir);
            return this;
        }

        public Dir withImportDir(Dir dir) {
            this.importDirs.add(dir);
            return this;
        }

        protected void writeModels(Path toDir) {
            try {
                if (!Files.exists(toDir)) {
                    Files.createDirectory(toDir);
                }
                writeModels(toDir, sourceModels);
                writeModels(toDir, importModels);
                sourceDirs.forEach(d -> d.writeModels(toDir.resolve(d.path)));
                importDirs.forEach(d -> d.writeModels(toDir.resolve(d.path)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static void writeModels(Path toDir, Map<String, String> models) throws Exception {
            for (Map.Entry<String, String> entry : models.entrySet()) {
                Files.write(toDir.resolve(entry.getKey()), entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public static final class Builder extends Dir {
        private Builder() {}

        @Override
        public Builder withSourceFile(String filename, String model) {
            super.withSourceFile(filename, model);
            return this;
        }

        @Override
        public Builder withImportFile(String filename, String model) {
            super.withImportFile(filename, model);
            return this;
        }

        @Override
        public Builder withSourceDir(Dir dir) {
            super.withSourceDir(dir);
            return this;
        }

        @Override
        public Builder withImportDir(Dir dir) {
            super.withImportDir(dir);
            return this;
        }

        public TestWorkspace build() {
            try {
                if (path == null) {
                    path = "test";
                }
                Path root = Files.createTempDirectory(path);
                root.toFile().deleteOnExit();

                List<String> sources = new ArrayList<>();
                sources.addAll(sourceModels.keySet());
                sources.addAll(sourceDirs.stream().map(d -> d.path).collect(Collectors.toList()));

                List<String> imports = new ArrayList<>();
                imports.addAll(importModels.keySet());
                imports.addAll(importDirs.stream().map(d -> d.path).collect(Collectors.toList()));

                SmithyBuildConfig config = SmithyBuildConfig.builder()
                        .version("1")
                        .sources(sources)
                        .imports(imports)
                        .build();
                String configString = Node.prettyPrintJson(MAPPER.serialize(config));
                Files.write(root.resolve("smithy-build.json"), configString.getBytes(StandardCharsets.UTF_8));

                writeModels(root);

                return new TestWorkspace(root);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
