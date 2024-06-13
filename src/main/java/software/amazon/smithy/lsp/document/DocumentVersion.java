/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import org.eclipse.lsp4j.Range;

/**
 * The Smithy version of the document, including the range it occupies.
 */
public final class DocumentVersion {
    private final Range range;
    private final String version;

    DocumentVersion(Range range, String version) {
        this.range = range;
        this.version = version;
    }

    /**
     * @return The range of the version statement
     */
    public Range range() {
        return range;
    }

    /**
     * @return The literal text of the version value
     */
    public String version() {
        return version;
    }
}
