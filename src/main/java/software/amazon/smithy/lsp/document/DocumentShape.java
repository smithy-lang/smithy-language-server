/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.util.Objects;
import org.eclipse.lsp4j.Range;

public final class DocumentShape {
    private final Range range;
    private final CharSequence shapeName;
    private Kind kind;
    private DocumentShape targetReference;

    DocumentShape(Range range, CharSequence shapeName, Kind kind) {
        this.range = range;
        this.shapeName = shapeName;
        this.kind = kind;
    }

    public Range range() {
        return range;
    }

    public CharSequence shapeName() {
        return shapeName;
    }

    public Kind kind() {
        return kind;
    }

    void setKind(Kind kind) {
        this.kind = kind;
    }

    public DocumentShape targetReference() {
        return targetReference;
    }

    void setTargetReference(DocumentShape targetReference) {
        this.targetReference = targetReference;
    }

    public boolean isKind(Kind other) {
        return this.kind.equals(other);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DocumentShape that = (DocumentShape) o;
        return Objects.equals(range, that.range) && Objects.equals(shapeName, that.shapeName) && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(range, shapeName, kind);
    }

    @Override
    public String toString() {
        return "DocumentShape{"
               + "range=" + range
               + ", shapeName=" + shapeName
               + ", kind=" + kind
               + ", targetReference=" + targetReference
               + '}';
    }

    public enum Kind {
        DefinedShape,
        DefinedMember,
        Elided,
        Targeted,
        Inline;
    }
}
