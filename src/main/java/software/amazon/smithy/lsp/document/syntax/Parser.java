/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document.syntax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.utils.SimpleParser;

/**
 * Parser for {@link Syntax.Node} and {@link Syntax.Statement}. See
 * {@link Syntax} for more details on the design of the parser.
 *
 * <p>This parser can be used to parse a single {@link Syntax.Node} by itself,
 * or to parse a list of {@link Syntax.Statement} in a Smithy file.
 */
final class Parser extends SimpleParser {
    final List<Syntax.Err> errors = new ArrayList<>();
    final List<Syntax.Statement> statements = new ArrayList<>();
    private final Document document;

    Parser(Document document) {
        super(document.borrowText());
        this.document = document;
    }

    Syntax.Node parseNode() {
        ws();
        return switch (peek()) {
            case '{' -> obj();
            case '"' -> str();
            case '[' -> arr();
            case '-' -> num();
            default -> {
                if (isDigit()) {
                    yield num();
                } else if (isIdentStart()) {
                    yield ident();
                }

                int start = position();
                do {
                    skip();
                } while (!isWs() && !isNodeStructuralBreakpoint() && !eof());
                int end = position();
                Syntax.Node.Err err = new Syntax.Node.Err("unexpected token " + document.copySpan(start, end));
                err.start = start;
                err.end = end;
                yield err;
            }
        };
    }

    void parseIdl() {
        try {
            ws();
            while (!eof()) {
                statement();
                ws();
            }
        } catch (Parser.Eof e) {
            // This is used to stop parsing when eof is encountered even if we're
            // within many layers of method calls.
            Syntax.Statement.Err err = new Syntax.Statement.Err(e.message);
            err.start = position();
            err.end = position();
            errors.add(err);
        }
    }

    private void setStart(Syntax.Item item) {
        if (eof()) {
            item.start = position() - 1;
        } else {
            item.start = position();
        }
    }

    private int positionForStart() {
        if (eof()) {
            return position() - 1;
        } else {
            return position();
        }
    }

    private void setEnd(Syntax.Item item) {
        item.end = position();
    }

    private void rewindTo(int pos) {
        int line = document.lineOfIndex(pos);
        int lineIndex = document.indexOfLine(line);
        this.rewind(pos, line + 1, pos - lineIndex + 1);
    }

    private Syntax.Node traitNode() {
        skip(); // '('
        ws();
        return switch (peek()) {
            case '{' -> obj();
            case '"' -> {
                int pos = position();
                Syntax.Node str = str();
                ws();
                if (is(':')) {
                    yield traitValueKvps(pos);
                } else {
                    yield str;
                }
            }
            case '[' -> arr();
            case '-' -> num();
            default -> {
                if (isDigit()) {
                    yield num();
                } else if (isIdentStart()) {
                    int pos = position();
                    Syntax.Node ident = nodeIdent();
                    ws();
                    if (is(':')) {
                        yield traitValueKvps(pos);
                    } else {
                        yield ident;
                    }
                } else if (is(')')) {
                    Syntax.Node.Kvps kvps = new Syntax.Node.Kvps();
                    setStart(kvps);
                    setEnd(kvps);
                    skip();
                    yield kvps;
                }

                int start = position();
                do {
                    skip();
                } while (!isWs() && !isStructuralBreakpoint() && !eof());
                int end = position();
                Syntax.Node.Err err;
                if (eof()) {
                    err = new Syntax.Node.Err("unexpected eof");
                } else {
                    err = new Syntax.Node.Err("unexpected token " + document.copySpan(start, end));
                }
                err.start = start;
                err.end = end;
                yield err;
            }
        };
    }

    private Syntax.Node traitValueKvps(int from) {
        rewindTo(from);
        Syntax.Node.Kvps kvps = new Syntax.Node.Kvps();
        setStart(kvps);
        while (!eof()) {
            if (is(')')) {
                setEnd(kvps);
                skip();
                return kvps;
            }

            Syntax.Node.Err kvpErr = kvp(kvps, ')');
            if (kvpErr != null) {
                errors.add(kvpErr);
            }

            ws();
        }
        kvps.end = position() - 1;
        return kvps;
    }

    private Syntax.Node nodeIdent() {
        int start = position();
        // assume there's _something_ here
        do {
            skip();
        } while (!isWs() && !isStructuralBreakpoint() && !eof());
        return new Syntax.Ident(start, position());
    }

    private Syntax.Node.Obj obj() {
        Syntax.Node.Obj obj = new Syntax.Node.Obj();
        setStart(obj);
        skip();
        ws();
        while (!eof()) {
            if (is('}')) {
                skip();
                setEnd(obj);
                return obj;
            }

            Syntax.Err kvpErr = kvp(obj.kvps, '}');
            if (kvpErr != null) {
                errors.add(kvpErr);
            }

            ws();
        }

        Syntax.Node.Err err = new Syntax.Node.Err("missing }");
        setStart(err);
        setEnd(err);
        errors.add(err);

        setEnd(obj);
        return obj;
    }

    private Syntax.Node.Err kvp(Syntax.Node.Kvps kvps, char close) {
        Syntax.Node keyValue = parseNode();
        Syntax.Node.Err err = null;
        Syntax.Node.Str key = null;
        switch (keyValue) {
            case Syntax.Node.Str s -> {
                key = s;
            }
            case Syntax.Node.Err e -> {
                err = e;
            }
            default -> {
                err = nodeErr(keyValue, "unexpected " + keyValue.type());
            }
        }

        ws();

        Syntax.Node.Kvp kvp = null;
        if (key != null) {
            kvp = new Syntax.Node.Kvp(key);
            kvps.add(kvp);
        }

        if (is(':')) {
            skip();
            ws();
        } else if (eof()) {
            return nodeErr("unexpected eof");
        } else {
            if (err != null) {
                errors.add(err);
            }

            err = nodeErr("expected :");
        }

        if (is(close)) {
            if (err != null) {
                errors.add(err);
            }

            return nodeErr("expected value");
        }

        Syntax.Node value = parseNode();
        if (value instanceof Syntax.Node.Err e) {
            if (err != null) {
                errors.add(err);
            }
            err = e;
        } else if (err == null) {
            kvp.value = value;
            return null;
        }

        return err;
    }

    private Syntax.Node.Arr arr() {
        Syntax.Node.Arr arr = new Syntax.Node.Arr();
        setStart(arr);
        skip();
        ws();
        while (!eof()) {
            if (is(']')) {
                skip();
                setEnd(arr);
                return arr;
            }

            Syntax.Node elem = parseNode();
            if (elem instanceof Syntax.Node.Err e) {
                errors.add(e);
            } else {
                arr.elements.add(elem);
            }
            ws();
        }

        Syntax.Node.Err err = nodeErr("missing ]");
        errors.add(err);

        setEnd(arr);
        return arr;
    }

    private Syntax.Node str() {
        int start = position();
        skip(); // '"'
        if (is('"')) {
            skip();

            if (is('"')) {
                skip();

                // text block
                int end = document.nextIndexOf("\"\"\"", position());
                if (end == -1) {
                    rewindTo(document.length() - 1);
                    Syntax.Node.Err err = new Syntax.Node.Err("unclosed text block");
                    err.start = start;
                    err.end = document.length();
                    return err;
                }

                rewindTo(end + 3);
                Syntax.Node.Str str = new Syntax.Node.Str();
                str.start = start;
                setEnd(str);
                return str;
            }

            skip();
            Syntax.Node.Str str = new Syntax.Node.Str();
            str.start = start;
            setEnd(str);
            return str;
        }

        int last = '"';

        // Potential micro-optimization - only loop while position < line end
        while (!isNl() && !eof()) {
            if (is('"') && last != '\\') {
                skip(); // '"'
                Syntax.Node.Str str = new Syntax.Node.Str();
                str.start = start;
                setEnd(str);
                return str;
            }
            last = peek();
            skip();
        }

        Syntax.Node.Err err = new Syntax.Node.Err("unclosed string literal");
        err.start = start;
        setEnd(err);
        return err;
    }

    private Syntax.Node num() {
        int start = position();
        while (!isWs() && !isNodeStructuralBreakpoint() && !eof()) {
            skip();
        }

        String token = document.copySpan(start, position());
        if (token == null) {
            throw new RuntimeException("unhandled eof in node num");
        }

        Syntax.Node value;
        try {
            BigDecimal numValue = new BigDecimal(token);
            value = new Syntax.Node.Num(numValue);
        } catch (NumberFormatException e) {
            value = new Syntax.Node.Err(String.format("%s is not a valid number", token));
        }
        value.start = start;
        setEnd(value);
        return value;
    }

    private boolean isNodeStructuralBreakpoint() {
        return switch (peek()) {
            case '{', '[', '}', ']', ',', ':', ')' -> true;
            default -> false;
        };
    }

    private Syntax.Node.Err nodeErr(Syntax.Node from, String message) {
        Syntax.Node.Err err = new Syntax.Node.Err(message);
        err.start = from.start;
        err.end = from.end;
        return err;
    }

    private Syntax.Node.Err nodeErr(String message) {
        Syntax.Node.Err err = new Syntax.Node.Err(message);
        setStart(err);
        setEnd(err);
        return err;
    }

    private void statement() {
        if (is('@')) {
            traitApplication();
        } else if (is('$')) {
            control();
        } else {
            // Shape, apply
            int start = position();
            Syntax.Ident ident = ident();
            if (ident.isEmpty()) {
                if (!isWs()) {
                    skip();
                }
                return;
            }

            sp();
            Syntax.Ident name = ident();
            if (name.isEmpty()) {
                Syntax.Statement.Incomplete incomplete = new Syntax.Statement.Incomplete(ident);
                incomplete.start = start;
                incomplete.end = position();
                statements.add(incomplete);

                if (!isWs()) {
                    skip();
                }
                return;
            }

            String identCopy = ident.copyValueFrom(document);

            switch (identCopy) {
                case "apply" -> {
                    apply(start, name);
                    return;
                }
                case "metadata" -> {
                    metadata(start, name);
                    return;
                }
                case "use" -> {
                    use(start, name);
                    return;
                }
                case "namespace" -> {
                    namespace(start, name);
                    return;
                }
                default -> {
                }
            }

            Syntax.Statement.ShapeDef shapeDef = new Syntax.Statement.ShapeDef(ident, name);
            shapeDef.start = start;
            setEnd(shapeDef);
            statements.add(shapeDef);

            sp();
            optionalForResourceAndMixins();
            ws();

            switch (identCopy) {
                case "enum", "intEnum" -> {
                    var block = startBlock();

                    ws();
                    while (!is('}') && !eof()) {
                        enumMember();
                        ws();
                    }

                    endBlock(block);
                }
                case "structure", "list", "map", "union" -> {
                    var block = startBlock();

                    ws();
                    while (!is('}') && !eof()) {
                        member();
                        ws();
                    }

                    endBlock(block);
                }
                case "resource", "service" -> {
                    var block = startBlock();

                    ws();
                    while (!is('}') && !eof()) {
                        nodeMember();
                        ws();
                    }

                    endBlock(block);
                }
                case "operation" -> {
                    var block = startBlock();
                    // This is different from the other member parsing because it needs more fine-grained loop/branch
                    // control to deal with inline structures
                    operationMembers();
                    endBlock(block);
                }
                default -> {
                }
            }
        }
    }

    private Syntax.Statement.Block startBlock() {
        Syntax.Statement.Block block = new Syntax.Statement.Block(statements.size());
        setStart(block);
        statements.add(block);
        if (is('{')) {
            skip();
        } else {
            addErr(position(), position(), "expected {");
            recoverToMemberStart();
        }
        return block;
    }

    private void endBlock(Syntax.Statement.Block block) {
        block.lastStatementIndex = statements.size() - 1;
        throwIfEofAndFinish("expected }", block); // This will stop execution
        skip(); // '}'
        setEnd(block);
    }

    private void operationMembers() {
        ws();
        while (!is('}') && !eof()) {
            int opMemberStart = position();
            Syntax.Ident memberName = ident();

            int colonPos = -1;
            sp();
            if (is(':')) {
                colonPos = position();
                skip(); // ':'
            } else {
                addErr(position(), position(), "expected :");
                if (isWs()) {
                    var memberDef = new Syntax.Statement.MemberDef(memberName);
                    memberDef.start = opMemberStart;
                    setEnd(memberDef);
                    statements.add(memberDef);
                    ws();
                    continue;
                }
            }

            if (is('=')) {
                skip(); // '='
                inlineMember(opMemberStart, memberName);
                ws();
                continue;
            }

            ws();

            if (isIdentStart()) {
                var opMemberDef = new Syntax.Statement.MemberDef(memberName);
                opMemberDef.start = opMemberStart;
                opMemberDef.colonPos = colonPos;
                opMemberDef.target = ident();
                setEnd(opMemberDef);
                statements.add(opMemberDef);
            } else {
                var nodeMemberDef = new Syntax.Statement.NodeMemberDef(memberName);
                nodeMemberDef.start = opMemberStart;
                nodeMemberDef.colonPos = colonPos;
                nodeMemberDef.value = parseNode();
                setEnd(nodeMemberDef);
                statements.add(nodeMemberDef);
            }

            ws();
        }
    }

    private void control() {
        int start = position();
        skip(); // '$'
        Syntax.Ident ident = ident();
        Syntax.Statement.Control control = new Syntax.Statement.Control(ident);
        control.start = start;
        statements.add(control);
        sp();

        if (!is(':')) {
            addErr(position(), position(), "expected :");
            if (isWs()) {
                setEnd(control);
                return;
            }
        } else {
            skip();
        }

        control.value = parseNode();
        setEnd(control);
    }

    private void apply(int start, Syntax.Ident name) {
        Syntax.Statement.Apply apply = new Syntax.Statement.Apply(name);
        apply.start = start;
        setEnd(apply);
        statements.add(apply);

        sp();
        if (is('@')) {
            traitApplication();
        } else if (is('{')) {
            var block = startBlock();

            ws();
            while (!is('}') && !eof()) {
                if (!is('@')) {
                    addErr(position(), position(), "expected trait");
                    return;
                }
                traitApplication();
                ws();
            }

            endBlock(block);
        } else {
            addErr(position(), position(), "expected trait or block");
        }
    }

    private void metadata(int start, Syntax.Ident name) {
        Syntax.Statement.Metadata metadata = new Syntax.Statement.Metadata(name);
        metadata.start = start;
        statements.add(metadata);

        sp();
        if (!is('=')) {
            addErr(position(), position(), "expected =");
            if (isWs()) {
                setEnd(metadata);
                return;
            }
        } else {
            skip();
        }
        metadata.value = parseNode();
        setEnd(metadata);
    }

    private void use(int start, Syntax.Ident name) {
        Syntax.Statement.Use use = new Syntax.Statement.Use(name);
        use.start = start;
        setEnd(use);
        statements.add(use);
    }

    private void namespace(int start, Syntax.Ident name) {
        Syntax.Statement.Namespace namespace = new Syntax.Statement.Namespace(name);
        namespace.start = start;
        setEnd(namespace);
        statements.add(namespace);
    }

    private void optionalForResourceAndMixins() {
        int maybeStart = position();
        Syntax.Ident maybe = optIdent();

        if (maybe.copyValueFrom(document).equals("for")) {
            sp();
            Syntax.Ident resource = ident();
            Syntax.Statement.ForResource forResource = new Syntax.Statement.ForResource(resource);
            forResource.start = maybeStart;
            setEnd(forResource);
            statements.add(forResource);
            sp();
            maybeStart = position();
            maybe = optIdent();
        }

        if (maybe.copyValueFrom(document).equals("with")) {
            sp();
            Syntax.Statement.Mixins mixins = new Syntax.Statement.Mixins();
            mixins.start = maybeStart;

            if (!is('[')) {
                addErr(position(), position(), "expected [");

                // If we're on an identifier, just assume the [ was meant to be there
                if (!isIdentStart()) {
                    setEnd(mixins);
                    statements.add(mixins);
                    return;
                }
            } else {
                skip();
            }

            ws();
            while (!isStructuralBreakpoint() && !eof()) {
                mixins.mixins.add(ident());
                ws();
            }

            if (is(']')) {
                skip(); // ']'
            } else {
                // We either have another structural breakpoint, or eof
                addErr(position(), position(), "expected ]");
            }

            setEnd(mixins);
            statements.add(mixins);
        }
    }

    private void member() {
        if (is('@')) {
            traitApplication();
        } else if (is('$')) {
            elidedMember();
        } else if (isIdentStart()) {
            int start = positionForStart();
            Syntax.Ident name = ident();
            Syntax.Statement.MemberDef memberDef = new Syntax.Statement.MemberDef(name);
            memberDef.start = start;
            statements.add(memberDef);

            sp();
            if (is(':')) {
                memberDef.colonPos = position();
                skip();
            } else {
                addErr(position(), position(), "expected :");
                if (isWs() || is('}')) {
                    setEnd(memberDef);
                    statements.add(memberDef);
                    return;
                }
            }
            ws();

            memberDef.target = ident();
            setEnd(memberDef);
            ws();

            if (is('=')) {
                skip();
                parseNode();
                ws();
            }

        } else {
            addErr(position(), position(),
                    "unexpected token " + peekSingleCharForMessage() + " expected trait or member");
            recoverToMemberStart();
        }
    }

    private void enumMember() {
        if (is('@')) {
            traitApplication();
        } else if (isIdentStart()) {
            int start = positionForStart();
            Syntax.Ident name = ident();
            var enumMemberDef = new Syntax.Statement.EnumMemberDef(name);
            enumMemberDef.start = start;
            statements.add(enumMemberDef);

            ws();
            if (is('=')) {
                skip(); // '='
                ws();
                enumMemberDef.value = parseNode();
            }
            setEnd(enumMemberDef);
        } else {
            addErr(position(), position(),
                    "unexpected token " + peekSingleCharForMessage() + " expected trait or member");
            recoverToMemberStart();
        }
    }

    private void elidedMember() {
        int start = positionForStart();
        skip(); // '$'
        Syntax.Ident name = ident();
        var elidedMemberDef = new Syntax.Statement.ElidedMemberDef(name);
        elidedMemberDef.start = start;
        setEnd(elidedMemberDef);
        statements.add(elidedMemberDef);
    }

    private void inlineMember(int start, Syntax.Ident name) {
        var inlineMemberDef = new Syntax.Statement.InlineMemberDef(name);
        inlineMemberDef.start = start;
        setEnd(inlineMemberDef);
        statements.add(inlineMemberDef);

        ws();
        while (is('@')) {
            traitApplication();
            ws();
        }
        throwIfEof("expected {");

        optionalForResourceAndMixins();
        ws();

        var block = startBlock();
        ws();
        while (!is('}') && !eof()) {
            member();
            ws();
        }
        endBlock(block);
    }

    private void nodeMember() {
        int start = positionForStart();
        Syntax.Ident name = ident();
        var nodeMemberDef = new Syntax.Statement.NodeMemberDef(name);
        nodeMemberDef.start = start;

        sp();
        if (is(':')) {
            nodeMemberDef.colonPos = position();
            skip(); // ':'
        } else {
            addErr(position(), position(), "expected :");
            if (isWs() || is('}')) {
                setEnd(nodeMemberDef);
                statements.add(nodeMemberDef);
                return;
            }
        }

        ws();
        nodeMemberDef.value = parseNode();
        setEnd(nodeMemberDef);
        statements.add(nodeMemberDef);
    }

    private void traitApplication() {
        int startPos = position();
        skip(); // '@'
        Syntax.Ident id = ident();
        var application = new Syntax.Statement.TraitApplication(id);
        application.start = startPos;
        statements.add(application);

        if (is('(')) {
            int start = position();
            application.value = traitNode();
            application.value.start = start;
            ws();
            if (is(')')) {
                setEnd(application.value);
                skip(); // ')'
            }
            // Otherwise, traitNode() probably ate it.
        }
        setEnd(application);
    }

    private Syntax.Ident optIdent() {
        if (!isIdentStart()) {
            return Syntax.Ident.EMPTY;
        }
        return ident();
    }

    private Syntax.Ident ident() {
        int start = position();
        if (!isIdentStart()) {
            addErr(start, start, "expected identifier");
            return Syntax.Ident.EMPTY;
        }

        do {
            skip();
        } while (isIdentChar());

        int end = position();
        if (start == end) {
            addErr(start, end, "expected identifier");
            return Syntax.Ident.EMPTY;
        }
        return new Syntax.Ident(start, end);
    }

    private void addErr(int start, int end, String message) {
        Syntax.Statement.Err err = new Syntax.Statement.Err(message);
        err.start = start;
        err.end = end;
        errors.add(err);
    }

    private void recoverToMemberStart() {
        ws();
        while (!isIdentStart() && !is('@') && !is('$') && !eof()) {
            skip();
            ws();
        }

        throwIfEof("expected member or trait");
    }

    private boolean isStructuralBreakpoint() {
        return switch (peek()) {
            case '{', '[', '(', '}', ']', ')', ':', '=', '@' -> true;
            default -> false;
        };
    }

    private boolean isIdentStart() {
        char peeked = peek();
        return Character.isLetter(peeked) || peeked == '_';
    }

    private boolean isIdentChar() {
        char peeked = peek();
        return Character.isLetterOrDigit(peeked) || peeked == '_' || peeked == '$' || peeked == '.' || peeked == '#';
    }

    private boolean isDigit() {
        return Character.isDigit(peek());
    }

    private boolean isNl() {
        return switch (peek()) {
            case '\n', '\r' -> true;
            default -> false;
        };
    }

    private boolean isWs() {
        return switch (peek()) {
            case '\n', '\r', ' ', '\t', ',' -> true;
            default -> false;
        };
    }

    private boolean is(char c) {
        return peek() == c;
    }

    private void throwIfEof(String message) {
        if (eof()) {
            throw new Eof(message);
        }
    }

    private void throwIfEofAndFinish(String message, Syntax.Item item) {
        if (eof()) {
            setEnd(item);
            throw new Eof(message);
        }
    }

    /**
     * Simple exception used to halt parsing when we reach the end of the file,
     * without having to bubble up multiple layers.
     */
    private static final class Eof extends RuntimeException {
        final String message;

        Eof(String message) {
            this.message = message;
        }
    }

    @Override
    public void ws() {
        while (this.isWs() || is('/')) {
            if (is('/')) {
                while (!isNl() && !eof()) {
                    this.skip();
                }
            } else {
                this.skip();
            }
        }
    }
}
