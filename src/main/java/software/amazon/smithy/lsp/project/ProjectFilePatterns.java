/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility methods for creating file patterns corresponding to meaningful
 * paths of a {@link Project}, such as sources and build files.
 */
public final class ProjectFilePatterns {
    private static final int BUILD_FILE_COUNT = 2 + ProjectConfigLoader.SMITHY_BUILD_EXTS.length;

    private ProjectFilePatterns() {
    }

    /**
     * @param project The project to get watch patterns for
     * @return A list of glob patterns used to watch Smithy files in the given project
     */
    public static List<String> getSmithyFileWatchPatterns(Project project) {
        return Stream.concat(project.sources().stream(), project.imports().stream())
                .map(path -> getSmithyFilePattern(path, true))
                .toList();
    }

    /**
     * @param project The project to get a path matcher for
     * @return A path matcher that can check if Smithy files belong to the given project
     */
    public static PathMatcher getSmithyFilesPathMatcher(Project project) {
        String pattern = Stream.concat(project.sources().stream(), project.imports().stream())
                .map(path -> getSmithyFilePattern(path, false))
                .collect(Collectors.joining(","));
        return FileSystems.getDefault().getPathMatcher("glob:{" + pattern + "}");
    }

    /**
     * @param project The project to get the watch pattern for
     * @return A glob pattern used to watch build files in the given project
     */
    public static String getBuildFilesWatchPattern(Project project) {
        Path root = project.root();
        String buildJsonPattern = escapeBackslashes(root.resolve(ProjectConfigLoader.SMITHY_BUILD).toString());
        String projectJsonPattern = escapeBackslashes(root.resolve(ProjectConfigLoader.SMITHY_PROJECT).toString());

        List<String> patterns = new ArrayList<>(BUILD_FILE_COUNT);
        patterns.add(buildJsonPattern);
        patterns.add(projectJsonPattern);
        for (String buildExt : ProjectConfigLoader.SMITHY_BUILD_EXTS) {
            patterns.add(escapeBackslashes(root.resolve(buildExt).toString()));
        }

        return "{" + String.join(",", patterns) + "}";
    }

    /**
     * @param project The project to get a path matcher for
     * @return A path matcher that can check if a file is a build file belonging to the given project
     */
    public static PathMatcher getBuildFilesPathMatcher(Project project) {
        // Watch pattern is the same as the pattern used for matching
        String pattern = getBuildFilesWatchPattern(project);
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    // When computing the pattern used for telling the client which files to watch, we want
    // to only watch .smithy/.json files. We don't need in the PathMatcher pattern (and it
    // is impossible anyway because we can't have a nested pattern).
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
            glob += ".{smithy,json}";
        }

        return escapeBackslashes(glob);
    }

    // In glob patterns, '\' is an escape character, so it needs to escaped
    // itself to work as a separator (i.e. for windows)
    private static String escapeBackslashes(String pattern) {
        return pattern.replace("\\", "\\\\");
    }
}
