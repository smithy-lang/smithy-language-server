/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.util.Set;
import org.eclipse.lsp4j.Range;

/**
 * The imports of a document, including the range they occupy.
 */
public final class DocumentImports {
    private final Range importsRange;
    private final Set<String> imports;

    DocumentImports(Range importsRange, Set<String> imports) {
        this.importsRange = importsRange;
        this.imports = imports;
    }

    /**
     * @return The range of the imports
     */
    public Range importsRange() {
        return importsRange;
    }

    /**
     * @return The set of imported shape ids. They are not guaranteed
     *  to be valid shape ids
     */
    public Set<String> imports() {
        return imports;
    }
}
