/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.protocol.LspAdapter;

public class ProjectManagerTest {
    @Test
    public void canCheckIfAFileIsTracked() {
        Path attachedRoot = ProjectTest.toPath(getClass().getResource("flat"));
        Project mainProject = ProjectLoader.load(attachedRoot).unwrap();

        ProjectManager manager = new ProjectManager();
        manager.updateMainProject(mainProject);

        String detachedUri = LspAdapter.toUri("/foo/bar");
        manager.createDetachedProject(detachedUri, "");

        String mainUri = LspAdapter.toUri(attachedRoot.resolve("main.smithy").toString());

        assertThat(manager.isTracked(mainUri), is(true));
        assertThat(manager.getProject(mainUri), notNullValue());
        assertThat(manager.getProject(mainUri).getSmithyFile(mainUri), notNullValue());

        assertThat(manager.isTracked(detachedUri), is(true));
        assertThat(manager.getProject(detachedUri), notNullValue());
        assertThat(manager.getProject(detachedUri).getSmithyFile(detachedUri), notNullValue());

        String untrackedUri = LspAdapter.toUri("/bar/baz.smithy");
        assertThat(manager.isTracked(untrackedUri), is(false));
        assertThat(manager.getProject(untrackedUri), nullValue());
    }
}
