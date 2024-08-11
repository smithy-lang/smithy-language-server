/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import org.eclipse.lsp4j.Range;

/**
 * A Shape definition OR reference within a document, including the range it occupies.
 *
 * <p>Shapes can be defined/referenced in various ways within a Smithy file, each
 * corresponding to a specific {@link Kind}. For each kind, the range spans the
 * shape name/id only.
 */
public record DocumentShape(
        Range range,
        CharSequence shapeName,
        Kind kind,
        DocumentShape targetReference
) {
    public boolean isKind(Kind other) {
        return this.kind.equals(other);
    }

    public boolean hasMemberTarget() {
        return isKind(Kind.DefinedMember) && targetReference() != null;
    }

    /**
     * The different kinds of {@link DocumentShape}s that can exist, corresponding to places
     * that a shape definition or reference may appear. This is non-exhaustive (for now).
     */
    public enum Kind {
        DefinedShape,
        DefinedMember,
        Elided,
        Targeted,
        Inline
    }
}
