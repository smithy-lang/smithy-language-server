/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import software.amazon.smithy.lsp.project.ProjectConfigLoader;

/**
 * Finds Project roots based on the location of smithy-build.json and .smithy-project.json.
 */
final class ProjectRootVisitor extends SimpleFileVisitor<Path> {
    private static final PathMatcher PROJECT_ROOT_MATCHER = FileSystems.getDefault().getPathMatcher(
            "glob:{" + ProjectConfigLoader.SMITHY_BUILD + "," + ProjectConfigLoader.SMITHY_PROJECT + "}");
    private static final int MAX_VISIT_DEPTH = 10;

    private final List<Path> roots = new ArrayList<>();

    /**
     * Walks through the file tree starting at {@code workspaceRoot}, collecting
     * paths of Project roots.
     *
     * @param workspaceRoot Root of the workspace to find projects in
     * @return A list of project roots
     * @throws IOException If an I/O error is thrown while walking files
     */
    static List<Path> findProjectRoots(Path workspaceRoot) throws IOException {
        ProjectRootVisitor visitor = new ProjectRootVisitor();
        Files.walkFileTree(workspaceRoot, EnumSet.noneOf(FileVisitOption.class), MAX_VISIT_DEPTH, visitor);
        return visitor.roots;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        Path name = file.getFileName();
        if (name != null && PROJECT_ROOT_MATCHER.matches(name)) {
            roots.add(file.getParent());
            return FileVisitResult.SKIP_SIBLINGS;
        }
        return FileVisitResult.CONTINUE;
    }
}
