/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import org.eclipse.lsp4j.Range;

/**
 * The namespace of the document, including the range it occupies.
 */
public final class DocumentNamespace {
    private final Range statementRange;
    private final CharSequence namespace;

    DocumentNamespace(Range statementRange, CharSequence namespace) {
        this.statementRange = statementRange;
        this.namespace = namespace;
    }

    /**
     * @return The range of the statement, including {@code namespace}
     */
    public Range statementRange() {
        return statementRange;
    }

    /**
     * @return The namespace of the document. Not guaranteed to be
     *  a valid namespace
     */
    public CharSequence namespace() {
        return namespace;
    }
}
