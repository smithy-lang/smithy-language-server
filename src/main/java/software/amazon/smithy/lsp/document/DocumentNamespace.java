/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import org.eclipse.lsp4j.Range;

/**
 * The namespace of the document, including the range it occupies.
 *
 * @param statementRange The range of the statement, including {@code namespace}
 * @param namespace The namespace of the document. Not guaranteed to be a valid namespace
 */
public record DocumentNamespace(Range statementRange, CharSequence namespace) {}
