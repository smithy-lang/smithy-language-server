/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;

/**
 * Represents different kinds of positions within an IDL file.
 */
sealed interface IdlPosition {
    /**
     * @return Whether the token at this position is definitely a reference
     *  to a root/top-level shape.
     */
    default boolean isRootShapeReference() {
        return switch (this) {
            case TraitId ignored -> true;
            case MemberTarget ignored -> true;
            case ShapeDef ignored -> true;
            case ForResource ignored -> true;
            case Mixin ignored -> true;
            case UseTarget ignored -> true;
            case ApplyTarget ignored -> true;
            default -> false;
        };
    }

    /**
     * @return The view this position is within.
     */
    StatementView view();

    record TraitId(StatementView view) implements IdlPosition {}

    record MemberTarget(StatementView view) implements IdlPosition {}

    record ShapeDef(StatementView view) implements IdlPosition {}

    record Mixin(StatementView view) implements IdlPosition {}

    record ApplyTarget(StatementView view) implements IdlPosition {}

    record UseTarget(StatementView view) implements IdlPosition {}

    record Namespace(StatementView view) implements IdlPosition {}

    record TraitValue(StatementView view, Syntax.Statement.TraitApplication application) implements IdlPosition {}

    record NodeMemberTarget(StatementView view, Syntax.Statement.NodeMemberDef nodeMember) implements IdlPosition {}

    record ControlKey(StatementView view) implements IdlPosition {}

    record MetadataKey(StatementView view) implements IdlPosition {}

    record MetadataValue(StatementView view, Syntax.Statement.Metadata metadata) implements IdlPosition {}

    record StatementKeyword(StatementView view) implements IdlPosition {}

    record MemberName(StatementView view, String name) implements IdlPosition {}

    record ElidedMember(StatementView view) implements IdlPosition {}

    record ForResource(StatementView view) implements IdlPosition {}

    record Unknown(StatementView view) implements IdlPosition {}

    static IdlPosition of(StatementView view) {
        int documentIndex = view.documentIndex();

        if (view.getStatement().isInKeyword(documentIndex)) {
            return new StatementKeyword(view);
        }

        return switch (view.getStatement()) {
            case Syntax.Statement.Incomplete incomplete
                    when incomplete.ident().isIn(documentIndex) -> new IdlPosition.StatementKeyword(view);

            case Syntax.Statement.Apply apply
                    when apply.id().isIn(documentIndex) -> new IdlPosition.ApplyTarget(view);

            case Syntax.Statement.Metadata m
                    when m.key().isIn(documentIndex) -> new IdlPosition.MetadataKey(view);

            case Syntax.Statement.Metadata m
                    when m.value() != null && m.value().isIn(documentIndex) -> new IdlPosition.MetadataValue(view, m);

            case Syntax.Statement.Control c
                    when c.key().isIn(documentIndex) -> new IdlPosition.ControlKey(view);

            case Syntax.Statement.TraitApplication t
                    when t.id().isEmpty() || t.id().isIn(documentIndex) -> new IdlPosition.TraitId(view);

            case Syntax.Statement.Use u
                    when u.use().isIn(documentIndex) -> new IdlPosition.UseTarget(view);

            case Syntax.Statement.MemberDef m
                    when m.inTarget(documentIndex) -> new IdlPosition.MemberTarget(view);

            case Syntax.Statement.MemberDef m
                    when m.name().isIn(documentIndex) -> new IdlPosition.MemberName(view, m.name().stringValue());

            case Syntax.Statement.NodeMemberDef m
                    when m.inValue(documentIndex) -> new IdlPosition.NodeMemberTarget(view, m);

            case Syntax.Statement.Namespace n
                    when n.namespace().isIn(documentIndex) -> new IdlPosition.Namespace(view);

            case Syntax.Statement.TraitApplication t
                    when t.value() != null && t.value().isIn(documentIndex) -> new IdlPosition.TraitValue(view, t);

            case Syntax.Statement.InlineMemberDef m
                    when m.name().isIn(documentIndex) -> new IdlPosition.MemberName(view, m.name().stringValue());

            case Syntax.Statement.ElidedMemberDef ignored -> new IdlPosition.ElidedMember(view);

            case Syntax.Statement.Mixins ignored -> new IdlPosition.Mixin(view);

            case Syntax.Statement.ShapeDef ignored -> new IdlPosition.ShapeDef(view);

            case Syntax.Statement.NodeMemberDef m -> new IdlPosition.MemberName(view, m.name().stringValue());

            case Syntax.Statement.Block ignored -> new IdlPosition.MemberName(view, "");

            case Syntax.Statement.ForResource ignored -> new IdlPosition.ForResource(view);

            default -> new IdlPosition.Unknown(view);
        };
    }
}
