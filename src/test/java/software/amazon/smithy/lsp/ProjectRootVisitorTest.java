/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static software.amazon.smithy.lsp.project.ProjectTest.toPath;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ProjectRootVisitorTest {
    @Test
    public void findsNestedRoot() throws Exception {
        Path root = toPath(getClass().getResource("project/nested"));
        List<Path> found = ProjectRootVisitor.findProjectRoots(root);
        assertThat(found, contains(UtilMatchers.endsWith(Path.of("nested/nested"))));
    }

    @Test
    public void findsMultiNestedRoots() throws Exception {
        Path root = toPath(getClass().getResource("project/multi-nested"));
        List<Path> found = ProjectRootVisitor.findProjectRoots(root);
        assertThat(found, containsInAnyOrder(
                UtilMatchers.endsWith(Path.of("multi-nested/nested-a")),
                UtilMatchers.endsWith(Path.of("multi-nested/nested-b"))));
    }
}
