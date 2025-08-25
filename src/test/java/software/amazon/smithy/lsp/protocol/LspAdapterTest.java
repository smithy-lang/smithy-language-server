/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.protocol;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

public class LspAdapterTest {
    @Test
    public void jarModelFilenameRoundTrip() {
        String jarModelFilename = "jar:file:/path%20with%20spaces/foo.jar!/bar.smithy";
        String jarUri = LspAdapter.toUri(jarModelFilename);

        assertThat(jarUri, equalTo("smithyjar:/path%20with%20spaces/foo.jar!/bar.smithy"));
        assertThat(LspAdapter.toPath(jarUri), equalTo(jarModelFilename));
    }

    @Test
    public void smithyjarRoundTrip() {
        String jarUri = "smithyjar:/path%20with%20spaces/foo.jar!/bar.smithy";
        String jarModelFilename = LspAdapter.toPath(jarUri);

        assertThat(jarModelFilename, equalTo("jar:file:/path%20with%20spaces/foo.jar!/bar.smithy"));
        assertThat(LspAdapter.toUri(jarModelFilename), equalTo(jarUri));
    }

    @Test
    public void aggressivelyEncodedSmithyjarRoundTrip() {
        String encodedJarUri = "smithyjar:/path%20with%20spaces/foo.jar%21/bar.smithy";
        String jarModelFilename = LspAdapter.toPath(encodedJarUri);

        assertThat(jarModelFilename, equalTo("jar:file:/path%20with%20spaces/foo.jar!/bar.smithy"));
        assertThat(LspAdapter.toUri(jarModelFilename), equalTo("smithyjar:/path%20with%20spaces/foo.jar!/bar.smithy"));
    }

    @Test
    public void aggressivelyEncodedSmithyJarToUrl() {
        String encodedJarUri = "smithyjar:/path%20with%20spaces/foo.jar%21/bar.smithy";

        assertDoesNotThrow(() -> LspAdapter.smithyJarUriToReadableUrl(encodedJarUri));
    }
}
