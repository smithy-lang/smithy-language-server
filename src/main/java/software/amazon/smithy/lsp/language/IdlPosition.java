/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import java.util.Optional;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.lsp.syntax.SyntaxSearch;

sealed interface IdlPosition {
    default boolean isEasyShapeReference() {
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

    SmithyFile smithyFile();

    record TraitId(SmithyFile smithyFile) implements IdlPosition {}

    record MemberTarget(SmithyFile smithyFile) implements IdlPosition {}

    record ShapeDef(SmithyFile smithyFile) implements IdlPosition {}

    record Mixin(SmithyFile smithyFile) implements IdlPosition {}

    record ApplyTarget(SmithyFile smithyFile) implements IdlPosition {}

    record UseTarget(SmithyFile smithyFile) implements IdlPosition {}

    record Namespace(SmithyFile smithyFile) implements IdlPosition {}

    record TraitValue(
            int documentIndex,
            int statementIndex,
            Syntax.Statement.TraitApplication traitApplication,
            SmithyFile smithyFile
    ) implements IdlPosition {}

    record NodeMemberTarget(
            int documentIndex,
            int statementIndex,
            Syntax.Statement.NodeMemberDef nodeMemberDef,
            SmithyFile smithyFile
    ) implements IdlPosition {}

    record ControlKey(SmithyFile smithyFile) implements IdlPosition {}

    record MetadataKey(SmithyFile smithyFile) implements IdlPosition {}

    record MetadataValue(
            int documentIndex,
            Syntax.Statement.Metadata metadata,
            SmithyFile smithyFile
    ) implements IdlPosition {}

    record StatementKeyword(SmithyFile smithyFile) implements IdlPosition {}

    record MemberName(int documentIndex, int statementIndex, SmithyFile smithyFile) implements IdlPosition {}

    record ElidedMember(int documentIndex, int statementIndex, SmithyFile smithyFile) implements IdlPosition {}

    record ForResource(SmithyFile smithyFile) implements IdlPosition {}

    static Optional<IdlPosition> at(SmithyFile smithyFile, Position position) {
        int documentIndex = smithyFile.document().indexOfPosition(position);
        if (documentIndex < 0) {
            return Optional.empty();
        }

        int statementIndex = SyntaxSearch.statementIndex(smithyFile.statements(), documentIndex);
        if (statementIndex < 0) {
            return Optional.empty();
        }

        Syntax.Statement statement = smithyFile.statements().get(statementIndex);
        IdlPosition idlPosition = switch (statement) {
            case Syntax.Statement.Incomplete incomplete
                    when incomplete.ident().isIn(documentIndex) -> new IdlPosition.StatementKeyword(smithyFile);

            case Syntax.Statement.ShapeDef shapeDef
                    when shapeDef.shapeType().isIn(documentIndex) -> new IdlPosition.StatementKeyword(smithyFile);

            case Syntax.Statement.Apply apply
                    when apply.id().isIn(documentIndex) -> new IdlPosition.ApplyTarget(smithyFile);

            case Syntax.Statement.Metadata m
                    when m.key().isIn(documentIndex) -> new IdlPosition.MetadataKey(smithyFile);

            case Syntax.Statement.Metadata m
                    when m.value() != null && m.value().isIn(documentIndex) -> new IdlPosition.MetadataValue(
                            documentIndex, m, smithyFile);

            case Syntax.Statement.Control c
                    when c.key().isIn(documentIndex) -> new IdlPosition.ControlKey(smithyFile);

            case Syntax.Statement.TraitApplication t
                    when t.id().isEmpty() || t.id().isIn(documentIndex) -> new IdlPosition.TraitId(smithyFile);

            case Syntax.Statement.Use u
                    when u.use().isIn(documentIndex) -> new IdlPosition.UseTarget(smithyFile);

            case Syntax.Statement.MemberDef m
                    when m.inTarget(documentIndex) -> new IdlPosition.MemberTarget(smithyFile);

            case Syntax.Statement.MemberDef m
                    when m.name().isIn(documentIndex) -> new IdlPosition.MemberName(
                            documentIndex, statementIndex, smithyFile);

            case Syntax.Statement.NodeMemberDef m
                    when m.inValue(documentIndex) -> new IdlPosition.NodeMemberTarget(
                            documentIndex, statementIndex, m, smithyFile);

            case Syntax.Statement.Namespace n
                    when n.namespace().isIn(documentIndex) -> new IdlPosition.Namespace(smithyFile);

            case Syntax.Statement.TraitApplication t
                    when t.value() != null && t.value().isIn(documentIndex) -> new IdlPosition.TraitValue(
                            documentIndex, statementIndex, t, smithyFile);

            case Syntax.Statement.ElidedMemberDef ignored -> new IdlPosition.ElidedMember(
                    documentIndex, statementIndex, smithyFile);

            case Syntax.Statement.Mixins ignored -> new IdlPosition.Mixin(smithyFile);

            case Syntax.Statement.ShapeDef ignored -> new IdlPosition.ShapeDef(smithyFile);

            case Syntax.Statement.NodeMemberDef ignored -> new IdlPosition.MemberName(
                    documentIndex, statementIndex, smithyFile);

            case Syntax.Statement.Block ignored -> new IdlPosition.MemberName(
                    documentIndex, statementIndex, smithyFile);

            case Syntax.Statement.ForResource ignored -> new IdlPosition.ForResource(smithyFile);

            default -> null;
        };

        return Optional.ofNullable(idlPosition);
    }
}
