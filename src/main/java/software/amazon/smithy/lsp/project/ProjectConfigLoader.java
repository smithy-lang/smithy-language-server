/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.utils.IoUtils;

/**
 * Loads {@link ProjectConfig}s from a given root directory
 *
 * <p>This aggregates configuration from multiple sources, including
 * {@link ProjectConfigLoader#SMITHY_BUILD},
 * {@link ProjectConfigLoader#SMITHY_BUILD_EXTS}, and
 * {@link ProjectConfigLoader#SMITHY_PROJECT}. Each of these are looked
 * for in the project root directory. If none are found, an empty smithy-build
 * is assumed. Any exceptions that occur are aggregated and will fail the load.
 *
 * <p>Aggregation is done as follows:
 * <ol>
 *     <li>
 *         Start with an empty {@link SmithyBuildConfig.Builder}. This will
 *         aggregate {@link SmithyBuildConfig} and {@link SmithyBuildExtensions}
 *     </li>
 *     <li>
 *         If a smithy-build.json exists, try to load it. If one doesn't exist,
 *         use an empty {@link SmithyBuildConfig} (with version "1"). Merge the result
 *         into the builder
 *     </li>
 *     <li>
 *         If any {@link ProjectConfigLoader#SMITHY_BUILD_EXTS} exist, try to load
 *         and merge them into a single {@link SmithyBuildExtensions.Builder}
 *     </li>
 *     <li>
 *         If a {@link ProjectConfigLoader#SMITHY_PROJECT} exists, try to load it.
 *         Otherwise use an empty {@link ProjectConfig.Builder}. This will be the
 *         result of the load
 *     </li>
 *     <li>
 *         Merge any {@link ProjectConfigLoader#SMITHY_BUILD_EXTS} into the original
 *         {@link SmithyBuildConfig.Builder} and build it
 *     </li>
 *     <li>
 *         Add all sources, imports, and MavenConfig from the {@link SmithyBuildConfig}
 *         to the {@link ProjectConfig.Builder}
 *     </li>
 *     <li>
 *         If the {@link ProjectConfig.Builder} doesn't specify an outputDirectory,
 *         use the one in {@link SmithyBuildConfig}, if present
 *     </li>
 * </ol>
 */
public final class ProjectConfigLoader {
    public static final String SMITHY_BUILD = "smithy-build.json";
    public static final String[] SMITHY_BUILD_EXTS = {"build/smithy-dependencies.json", ".smithy.json"};
    public static final String SMITHY_PROJECT = ".smithy-project.json";

    private static final Logger LOGGER = Logger.getLogger(ProjectConfigLoader.class.getName());
    private static final SmithyBuildConfig DEFAULT_SMITHY_BUILD = SmithyBuildConfig.builder().version("1").build();
    private static final NodeMapper NODE_MAPPER = new NodeMapper();

    private ProjectConfigLoader() {
    }

    static Result<ProjectConfig, List<Exception>> loadFromRoot(Path workspaceRoot) {
        SmithyBuildConfig.Builder builder = SmithyBuildConfig.builder();
        List<Exception> exceptions = new ArrayList<>();

        // TODO: We don't handle cases where the smithy-build.json isn't in the top level of the root.
        //  In order to do so, we probably need to be able to keep track of multiple projects.
        Path smithyBuildPath = workspaceRoot.resolve(SMITHY_BUILD);
        if (Files.isRegularFile(smithyBuildPath)) {
            LOGGER.info("Loading smithy-build.json from " + smithyBuildPath);
            Result<SmithyBuildConfig, Exception> result = Result.ofFallible(() ->
                    SmithyBuildConfig.load(smithyBuildPath));
            result.get().ifPresent(builder::merge);
            result.getErr().ifPresent(exceptions::add);
        } else {
            LOGGER.info("No smithy-build.json found at " + smithyBuildPath + ", defaulting to empty config.");
            builder.merge(DEFAULT_SMITHY_BUILD);
        }

        SmithyBuildExtensions.Builder extensionsBuilder = SmithyBuildExtensions.builder();
        for (String ext : SMITHY_BUILD_EXTS) {
            Path extPath = workspaceRoot.resolve(ext);
            if (Files.isRegularFile(extPath)) {
                Result<SmithyBuildExtensions, Exception> result = Result.ofFallible(() ->
                        loadSmithyBuildExtensions(extPath));
                result.get().ifPresent(extensionsBuilder::merge);
                result.getErr().ifPresent(exceptions::add);
            }
        }

        ProjectConfig.Builder finalConfigBuilder = ProjectConfig.builder();
        Path smithyProjectPath = workspaceRoot.resolve(SMITHY_PROJECT);
        if (Files.isRegularFile(smithyProjectPath)) {
            LOGGER.info("Loading .smithy-project.json from " + smithyProjectPath);
            Result<ProjectConfig.Builder, Exception> result = Result.ofFallible(() ->
                    ProjectConfig.Builder.load(smithyProjectPath));
            if (result.isOk()) {
                finalConfigBuilder = result.unwrap();
            } else {
                exceptions.add(result.unwrapErr());
            }
        }

        if (!exceptions.isEmpty()) {
            return Result.err(exceptions);
        }

        builder.merge(extensionsBuilder.build().asSmithyBuildConfig());
        SmithyBuildConfig config = builder.build();
        finalConfigBuilder.addSources(config.getSources()).addImports(config.getImports());
        config.getMaven().ifPresent(finalConfigBuilder::mavenConfig);
        if (finalConfigBuilder.outputDirectory == null) {
            config.getOutputDirectory().ifPresent(finalConfigBuilder::outputDirectory);
        }
        return Result.ok(finalConfigBuilder.build());
    }

    private static SmithyBuildExtensions loadSmithyBuildExtensions(Path path) {
        // NOTE: This is the legacy way we loaded build extensions. It used to throw a checked exception.
        String content = IoUtils.readUtf8File(path);
        ObjectNode node = Node.parseJsonWithComments(content, path.toString()).expectObjectNode();
        SmithyBuildExtensions config = NODE_MAPPER.deserialize(node, SmithyBuildExtensions.class);
        config.mergeMavenFromSmithyBuildConfig(SmithyBuildConfig.load(path));
        return config;
    }
}
