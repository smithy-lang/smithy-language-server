/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * The type of build file.
 *
 * <p>The language server supports loading project config from multiple kinds
 *  of files.
 */
public enum BuildFileType {
    /**
     * Primary smithy-build configuration file used by most projects.
     *
     * @see software.amazon.smithy.build.model.SmithyBuildConfig
     * @see <a href="https://smithy.io/2.0/guides/smithy-build-json.html">smithy-build.json</a>
     */
    SMITHY_BUILD("smithy-build.json"),

    /**
     * A config file used specifically for the language server from before
     * maven deps from smithy-build.json were supported.
     *
     * @see SmithyBuildExtensions
     */
    SMITHY_BUILD_EXT_0("build" + File.separator + "smithy-dependencies.json"),

    /**
     * A config file used specifically for the language server from before
     * maven deps from smithy-build.json were supported.
     *
     * @see SmithyBuildExtensions
     */
    SMITHY_BUILD_EXT_1(".smithy.json"),

    /**
     * A config file used specifically for the language server to specify
     * project config for a project that isn't specifying sources and
     * dependencies in smithy-build.json, typically some external build
     * system is being used.
     *
     * @see SmithyProjectJson
     */
    SMITHY_PROJECT(".smithy-project.json"),;

    /**
     * The filenames of all {@link BuildFileType}s.
     */
    public static final List<String> ALL_FILENAMES = Arrays.stream(BuildFileType.values())
            .map(BuildFileType::filename)
            .toList();

    private final String filename;

    BuildFileType(String filename) {
        this.filename = filename;
    }

    /**
     * @return The filename that denotes this {@link BuildFileType}.
     */
    public String filename() {
        return filename;
    }

    boolean supportsMavenConfiguration() {
        return switch (this) {
            case SMITHY_BUILD, SMITHY_BUILD_EXT_0, SMITHY_BUILD_EXT_1 -> true;
            default -> false;
        };
    }
}
