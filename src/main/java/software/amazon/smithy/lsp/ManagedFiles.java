/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import software.amazon.smithy.lsp.document.Document;

/**
 * Provides access to {@link Document}s managed by the server.
 *
 * <p>A document is _managed_ if its state is controlled by the lifecycle methods
 * didOpen, didClose, didChange, didSave. In other words, reading from disk _may_
 * not provide the accurate file content.
 */
public interface ManagedFiles {
    /**
     * @param uri Uri of the document to get
     * @return The document if found and it is managed, otherwise {@code null}
     */
    Document getManagedDocument(String uri);
}
