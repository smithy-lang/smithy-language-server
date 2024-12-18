/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.protocol.LspAdapter;

/**
 * The Smithy version of the document, including the range it occupies.
 *
 * @param range The range of the version statement
 * @param version The literal text of the version value
 */
public record DocumentVersion(Range range, String version) {
    static final DocumentVersion EMPTY = new DocumentVersion(LspAdapter.origin(), "");
}
