/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

import java.nio.CharBuffer;
import org.eclipse.lsp4j.Range;

/**
 * An inaccurate representation of an identifier within a model. It is
 * inaccurate in the sense that the string value it references isn't
 * necessarily a valid identifier, it just looks like an identifier.
 *
 * @param type The type of the id
 * @param idSlice A borrowed slice containing the id's value
 * @param range The range the id occupies
 */
public record DocumentId(Type type, CharBuffer idSlice, Range range) {
    /**
     * Represents the different kinds of identifiers that can be used to match.
     */
    public enum Type {
        /**
         * Just a shape name, no namespace or member.
         */
        ID,

        /**
         * Same as {@link Type#ID}, but with a namespace.
         */
        ABSOLUTE_ID,

        /**
         * Just a namespace - will have one or more {@code .}.
         */
        NAMESPACE,

        /**
         * Same as {@link Type#ABSOLUTE_ID}, but with a member - will have a {@code $}.
         */
        ABSOLUTE_WITH_MEMBER,

        /**
         * Same as {@link Type#ID}, but with a member - will have a {@code $}.
         */
        RELATIVE_WITH_MEMBER
    }

    public String copyIdValue() {
        return idSlice.toString();
    }
}
