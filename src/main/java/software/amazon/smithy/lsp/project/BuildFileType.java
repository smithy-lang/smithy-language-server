/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public enum BuildFileType {
    SMITHY_BUILD("smithy-build.json"),
    SMITHY_BUILD_EXT_0("build" + File.separator + "smithy-projectDependencies.json"),
    SMITHY_BUILD_EXT_1(".smithy.json"),
    SMITHY_PROJECT(".smithy-project.json"),;

    public static final List<String> ALL_FILENAMES = Arrays.stream(BuildFileType.values())
            .map(BuildFileType::filename)
            .toList();

    private final String filename;

    BuildFileType(String filename) {
        this.filename = filename;
    }

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
