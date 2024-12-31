/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp;

import software.amazon.smithy.lsp.document.Document;

public interface ManagedFiles {
    /**
     * @param uri Uri of the document to get
     * @return The document if found and it is managed, otherwise {@code null}
     */
    Document getManagedDocument(String uri);
}
