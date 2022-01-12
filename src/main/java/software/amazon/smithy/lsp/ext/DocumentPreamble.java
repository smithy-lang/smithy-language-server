/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import java.util.Set;
import org.eclipse.lsp4j.Range;

public final class DocumentPreamble {
    private final Range namespace;
    private final Range useBlock;
    private final Set<String> imports;
    private final boolean blankSeparated;

    /**
     * Document preamble represents some meta information about a Smithy document (potentially mid-edit)
     * This information is required to correctly implement features such as auto-import of definitions
     * on completions.
     *
     * @param namespace      position of namespace declaration
     * @param useBlock       start and end of the use statements block
     * @param imports        set of imported (fully qualified) identifiers
     * @param blankSeparated whether document preamble is separated from other definitions by newline(s)
     */
    public DocumentPreamble(Range namespace, Range useBlock, Set<String> imports, boolean blankSeparated) {
        this.namespace = namespace;
        this.useBlock = useBlock;
        this.imports = imports;
        this.blankSeparated = blankSeparated;
    }

    public Range getUseBlockRange() {
        return useBlock;
    }

    public Range getNamespaceRange() {
        return namespace;
    }

    public boolean hasImport(String i) {
        return imports.contains(i);
    }

    public boolean isBlankSeparated() {
        return this.blankSeparated;
    }

    @Override
    public String toString() {
        return "DocumentPreamble(namespace=" + namespace + ", useBlock=" + useBlock + ")";
    }
}
