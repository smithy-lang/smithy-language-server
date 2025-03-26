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
         * A root shape id, i.e. without trailing '$member'. May or may not
         * have a leading namespace.
         */
        ROOT,

        /**
         * A shape id with a member, i.e. with trailing '$member'. May or may
         * not have a leading namespace. May or may not have a root shape name
         * before the '$member'.
         */
        MEMBER
    }

    public String copyIdValue() {
        return idSlice.toString();
    }

    /**
     * @return The value of the id without a leading '$'
     */
    public String copyIdValueForElidedMember() {
        String idValue = copyIdValue();
        if (idValue.startsWith("$")) {
            return idValue.substring(1);
        }
        return idValue;
    }
}
