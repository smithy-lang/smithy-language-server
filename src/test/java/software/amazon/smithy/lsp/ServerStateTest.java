/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectTest;
import software.amazon.smithy.lsp.protocol.LspAdapter;

public class ServerStateTest {
    @Test
    public void canCheckIfAFileIsTracked() {
        Path attachedRoot = ProjectTest.toPath(getClass().getResource("project/flat"));
        ServerState manager = new ServerState();
        Project mainProject = ProjectLoader.load(attachedRoot, manager).unwrap();

        manager.attachedProjects().put("main", mainProject);

        String detachedUri = LspAdapter.toUri("/foo/bar");
        manager.createDetachedProject(detachedUri, "");

        String mainUri = LspAdapter.toUri(attachedRoot.resolve("main.smithy").toString());

        assertThat(manager.findProjectAndFile(mainUri), notNullValue());
        assertThat(manager.findProjectAndFile(mainUri).project().getSmithyFile(mainUri), notNullValue());

        assertThat(manager.findProjectAndFile(detachedUri), notNullValue());
        assertThat(manager.findProjectAndFile(detachedUri).project().getSmithyFile(detachedUri), notNullValue());

        String untrackedUri = LspAdapter.toUri("/bar/baz.smithy");
        assertThat(manager.findProjectAndFile(untrackedUri), nullValue());
    }
}
