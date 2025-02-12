/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectConfigLoader;

/**
 * Utility methods for computing glob patterns that match against Smithy files
 * or build files in Projects and workspaces.
 */
final class FilePatterns {
    private FilePatterns() {
    }

    /**
     * @param project The project to get watch patterns for
     * @return A list of glob patterns used to watch Smithy files in the given project
     */
    static List<String> getSmithyFileWatchPatterns(Project project) {
        return Stream.concat(project.sources().stream(), project.imports().stream())
                .map(path -> getSmithyFilePattern(path, true))
                .toList();
    }

    /**
     * @param project The project to get a path matcher for
     * @return A path matcher that can check if Smithy files belong to the given project
     */
    static PathMatcher getSmithyFilesPathMatcher(Project project) {
        String pattern = Stream.concat(project.sources().stream(), project.imports().stream())
                .map(path -> getSmithyFilePattern(path, false))
                .collect(Collectors.joining(","));
        return toPathMatcher("{" + pattern + "}");
    }

    /**
     * @param root The root to get the watch pattern for
     * @return A glob pattern used to watch build files in the given workspace
     */
    static String getWorkspaceBuildFilesWatchPattern(Path root) {
        return getBuildFilesPattern(root, true);
    }

    /**
     * @param root The root to get a path matcher for
     * @return A path matcher that can check if a file is a build file within the given workspace
     */
    static PathMatcher getWorkspaceBuildFilesPathMatcher(Path root) {
        String pattern = getWorkspaceBuildFilesWatchPattern(root);
        return toPathMatcher(pattern);
    }

    /**
     * @param project The project to get a path matcher for
     * @return A path matcher that can check if a file is a build file belonging to the given project
     */
    static PathMatcher getProjectBuildFilesPathMatcher(Project project) {
        String pattern = getBuildFilesPattern(project.root(), false);
        return toPathMatcher(pattern);
    }

    private static PathMatcher toPathMatcher(String globPattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + globPattern);
    }

    // Patterns for the workspace need to match on all build files in all subdirectories,
    // whereas patterns for projects only look at the top level (because project locations
    // are defined by the presence of these build files).
    private static String getBuildFilesPattern(Path root, boolean isWorkspacePattern) {
        String rootString = root.toString();
        if (!rootString.endsWith(File.separator)) {
            rootString += File.separator;
        }

        if (isWorkspacePattern) {
            rootString += "**" + File.separator;
        }

        return escapeBackslashes(rootString + "{" + String.join(",", ProjectConfigLoader.PROJECT_BUILD_FILES) + "}");
    }

    // When computing the pattern used for telling the client which files to watch, we want
    // to only watch .smithy/.json files. We don't need it in the PathMatcher pattern because
    // we only need to match files, not listen for specific changes (and it is impossible anyway
    // because we can't have a nested pattern).
    private static String getSmithyFilePattern(Path path, boolean isWatcherPattern) {
        String glob = path.toString();
        if (glob.endsWith(".smithy") || glob.endsWith(".json")) {
            return escapeBackslashes(glob);
        }

        if (!glob.endsWith(File.separator)) {
            glob += File.separator;
        }
        glob += "**";

        if (isWatcherPattern) {
            glob += "/*.{smithy,json}";
        }

        return escapeBackslashes(glob);
    }

    // In glob patterns, '\' is an escape character, so it needs to escaped
    // itself to work as a separator (i.e. for windows)
    private static String escapeBackslashes(String pattern) {
        return pattern.replace("\\", "\\\\");
    }
}
