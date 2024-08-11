/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document;

/**
 * Represents what kind of construct might exist at a certain position in a document.
 */
public enum DocumentPositionContext {
    /**
     * Within a trait id, that is anywhere from the {@code @} to the start of the
     * trait's body, or its end (if there is no trait body).
     */
    TRAIT,

    /**
     * Within the target of a member.
     */
    MEMBER_TARGET,

    /**
     * Within a shape definition, specifically anywhere from the beginning of
     * the shape type token, and the end of the shape name token. Does not
     * include members.
     */
    SHAPE_DEF,

    /**
     * Within a mixed in shape, specifically in the {@code []} next to {@code with}.
     */
    MIXIN,

    /**
     * Within the target (shape id) of a {@code use} statement.
     */
    USE_TARGET,

    /**
     * An unknown or indeterminate position.
     */
    OTHER
}
