/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

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
    private final boolean isJson;

    private Parser(Document document, boolean isJson) {
        super(document.borrowText());
        this.document = document;
        this.isJson = isJson;
    }

    static Parser forIdl(Document document) {
        return new Parser(document, false);
    }

    static Parser forJson(Document document) {
        return new Parser(document, true);
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
            addError(err);
        }
    }

    void parseIdlBetween(int start, int end) {
        try {
            rewindTo(start);
            ws();
            while (!eof() && position() < end) {
                statement();
                ws();
            }
        } catch (Parser.Eof e) {
            Syntax.Statement.Err err = new Syntax.Statement.Err(e.message);
            err.start = position();
            err.end = position();
            addError(err);
        }
    }

    private void addStatement(Syntax.Statement statement) {
        statements.add(statement);
    }

    private void addError(Syntax.Err err) {
        errors.add(err);
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
                addError(kvpErr);
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
        int end = position();
        return new Syntax.Ident(start, end, document.copySpan(start, end));
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
                obj.kvps.start = obj.start;
                obj.kvps.end = obj.end;
                return obj;
            }

            if (isJson && is(',')) {
                Syntax.Node.Err err = new Syntax.Node.Err("expected key");
                setStart(err);
                skip();
                setEnd(err);
                ws();
                continue;
            }

            Syntax.Err kvpErr = kvp(obj.kvps, '}');
            if (kvpErr != null) {
                addError(kvpErr);
            }

            ws();
            if (isJson && is(',')) {
                skip();
                ws();
            }
        }
        Syntax.Node.Err err = new Syntax.Node.Err("missing }");
        setStart(err);
        setEnd(err);
        addError(err);

        setEnd(obj);
        return obj;
    }

    private Syntax.Node.Err kvp(Syntax.Node.Kvps kvps, char close) {
        int start = positionForStart();
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
            kvp.start = start;
            kvps.add(kvp);
        }

        if (is(':')) {
            if (kvp != null) {
                kvp.colonPos = position();
            }
            skip();
            ws();
        } else if (eof()) {
            return nodeErr("unexpected eof");
        } else {
            if (err != null) {
                addError(err);
            }

            err = nodeErr("expected :");
        }

        if (is(close)) {
            if (err != null) {
                addError(err);
            }

            return nodeErr("expected value");
        }

        if (is(',')) {
            skip();
            if (kvp != null) {
                setEnd(kvp);
            }
            if (err != null) {
                addError(err);
            }

            return nodeErr("expected value");
        }

        Syntax.Node value = parseNode();
        if (value instanceof Syntax.Node.Err e) {
            if (err != null) {
                addError(err);
            }
            err = e;
        } else if (err == null) {
            kvp.value = value;
            kvp.end = value.end;
            if (is(',')) {
                skip();
            }
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
                addError(e);
            } else {
                arr.elements.add(elem);
            }
            ws();
            if (is(',')) {
                skip();
            }
            ws();
        }

        Syntax.Node.Err err = nodeErr("missing ]");
        addError(err);

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
                int strEnd = position();
                return new Syntax.Node.Str(start, strEnd, document.copySpan(start + 3, strEnd - 3));
            }

            // Empty string
            skip();
            int strEnd = position();
            return new Syntax.Node.Str(start, strEnd, "");
        }

        int last = '"';

        // Potential micro-optimization - only loop while position < line end
        while (!isNl() && !eof()) {
            if (is('"') && last != '\\') {
                skip(); // '"'
                int strEnd = position();
                return new Syntax.Node.Str(start, strEnd, document.copySpan(start + 1, strEnd - 1));
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

    private void skipUntilStatementStart() {
        while (!is('@') && !is('$') && !isIdentStart() && !eof()) {
            skip();
        }
    }

    private void statement() {
        if (is('@')) {
            traitApplication(null);
        } else if (is('$')) {
            control();
        } else {
            // Shape, apply
            int start = position();
            Syntax.Ident ident = ident();
            if (ident.isEmpty()) {
                if (!isWs()) {
                    // TODO: Capture all this in an error
                    skipUntilStatementStart();
                }
                return;
            }

            sp();
            Syntax.Ident name = ident();
            if (name.isEmpty()) {
                Syntax.Statement.Incomplete incomplete = new Syntax.Statement.Incomplete(ident);
                incomplete.start = start;
                incomplete.end = position();
                addStatement(incomplete);

                if (!isWs()) {
                    skip();
                }
                return;
            }

            String identCopy = ident.stringValue();

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
            addStatement(shapeDef);

            sp();
            optionalForResourceAndMixins();
            ws();

            switch (identCopy) {
                case "enum", "intEnum" -> {
                    var block = startBlock(null);

                    ws();
                    while (!is('}') && !eof()) {
                        enumMember(block);
                        ws();
                    }

                    endBlock(block);
                }
                case "structure", "list", "map", "union" -> {
                    var block = startBlock(null);

                    ws();
                    while (!is('}') && !eof()) {
                        member(block);
                        ws();
                    }

                    endBlock(block);
                }
                case "resource", "service" -> {
                    var block = startBlock(null);

                    ws();
                    while (!is('}') && !eof()) {
                        nodeMember(block);
                        ws();
                    }

                    endBlock(block);
                }
                case "operation" -> {
                    var block = startBlock(null);
                    // This is different from the other member parsing because it needs more fine-grained loop/branch
                    // control to deal with inline structures
                    operationMembers(block);
                    endBlock(block);
                }
                default -> {
                }
            }
        }
    }

    private Syntax.Statement.Block startBlock(Syntax.Statement.Block parent) {
        Syntax.Statement.Block block = new Syntax.Statement.Block(parent, statements.size());
        setStart(block);
        addStatement(block);
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

    private void operationMembers(Syntax.Statement.Block parent) {
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
                    var memberDef = new Syntax.Statement.MemberDef(parent, memberName);
                    memberDef.start = opMemberStart;
                    setEnd(memberDef);
                    addStatement(memberDef);
                    ws();
                    continue;
                }
            }

            if (is('=')) {
                skip(); // '='
                inlineMember(parent, opMemberStart, memberName);
                ws();
                continue;
            }

            ws();

            if (isIdentStart()) {
                var opMemberDef = new Syntax.Statement.MemberDef(parent, memberName);
                opMemberDef.start = opMemberStart;
                opMemberDef.colonPos = colonPos;
                opMemberDef.target = ident();
                setEnd(opMemberDef);
                addStatement(opMemberDef);
            } else {
                var nodeMemberDef = new Syntax.Statement.NodeMemberDef(parent, memberName);
                nodeMemberDef.start = opMemberStart;
                nodeMemberDef.colonPos = colonPos;
                nodeMemberDef.value = parseNode();
                setEnd(nodeMemberDef);
                addStatement(nodeMemberDef);
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
        addStatement(control);
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
        addStatement(apply);

        sp();
        if (is('@')) {
            traitApplication(null);
        } else if (is('{')) {
            var block = startBlock(null);

            ws();
            while (!is('}') && !eof()) {
                if (!is('@')) {
                    addErr(position(), position(), "expected trait");
                    return;
                }
                traitApplication(block);
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
        addStatement(metadata);

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
        addStatement(use);
    }

    private void namespace(int start, Syntax.Ident name) {
        Syntax.Statement.Namespace namespace = new Syntax.Statement.Namespace(name);
        namespace.start = start;
        setEnd(namespace);
        addStatement(namespace);
    }

    private void optionalForResourceAndMixins() {
        int maybeStart = position();
        Syntax.Ident maybe = optIdent();

        if (maybe.stringValue().equals("for")) {
            sp();
            Syntax.Ident resource = ident();
            Syntax.Statement.ForResource forResource = new Syntax.Statement.ForResource(resource);
            forResource.start = maybeStart;
            addStatement(forResource);
            ws();
            setEnd(forResource);
            maybeStart = position();
            maybe = optIdent();
        }

        if (maybe.stringValue().equals("with")) {
            sp();
            Syntax.Statement.Mixins mixins = new Syntax.Statement.Mixins();
            mixins.start = maybeStart;

            if (!is('[')) {
                addErr(position(), position(), "expected [");

                // If we're on an identifier, just assume the [ was meant to be there
                if (!isIdentStart()) {
                    setEnd(mixins);
                    addStatement(mixins);
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
            addStatement(mixins);
        }
    }

    private void member(Syntax.Statement.Block parent) {
        if (is('@')) {
            traitApplication(parent);
        } else if (is('$')) {
            elidedMember(parent);
        } else if (isIdentStart()) {
            int start = positionForStart();
            Syntax.Ident name = ident();
            Syntax.Statement.MemberDef memberDef = new Syntax.Statement.MemberDef(parent, name);
            memberDef.start = start;
            addStatement(memberDef);

            sp();
            if (is(':')) {
                memberDef.colonPos = position();
                skip();
            } else {
                addErr(position(), position(), "expected :");
                if (isWs() || is('}')) {
                    setEnd(memberDef);
                    addStatement(memberDef);
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

    private void enumMember(Syntax.Statement.Block parent) {
        if (is('@')) {
            traitApplication(parent);
        } else if (isIdentStart()) {
            int start = positionForStart();
            Syntax.Ident name = ident();
            var enumMemberDef = new Syntax.Statement.EnumMemberDef(parent, name);
            enumMemberDef.start = start;
            setEnd(enumMemberDef); // Set the enumMember end right after ident processed for simple enum member.
            addStatement(enumMemberDef);

            ws();
            if (is('=')) {
                skip(); // '='
                ws();
                enumMemberDef.value = parseNode();
                setEnd(enumMemberDef); // Override the previous enumMember end if assignment exists.
            }
        } else {
            addErr(position(), position(),
                    "unexpected token " + peekSingleCharForMessage() + " expected trait or member");
            recoverToMemberStart();
        }
    }

    private void elidedMember(Syntax.Statement.Block parent) {
        int start = positionForStart();
        skip(); // '$'
        Syntax.Ident name = ident();
        var elidedMemberDef = new Syntax.Statement.ElidedMemberDef(parent, name);
        elidedMemberDef.start = start;
        setEnd(elidedMemberDef);
        addStatement(elidedMemberDef);
    }

    private void inlineMember(Syntax.Statement.Block parent, int start, Syntax.Ident name) {
        var inlineMemberDef = new Syntax.Statement.InlineMemberDef(parent, name);
        inlineMemberDef.start = start;
        setEnd(inlineMemberDef);
        addStatement(inlineMemberDef);

        ws();
        while (is('@')) {
            traitApplication(parent);
            ws();
        }
        throwIfEof("expected {");

        optionalForResourceAndMixins();
        ws();

        var block = startBlock(parent);
        ws();
        while (!is('}') && !eof()) {
            member(block);
            ws();
        }
        endBlock(block);
    }

    private void nodeMember(Syntax.Statement.Block parent) {
        int start = positionForStart();
        Syntax.Ident name = ident();
        var nodeMemberDef = new Syntax.Statement.NodeMemberDef(parent, name);
        nodeMemberDef.start = start;

        sp();
        if (is(':')) {
            nodeMemberDef.colonPos = position();
            skip(); // ':'
        } else {
            addErr(position(), position(), "expected :");
            if (isWs() || is('}')) {
                setEnd(nodeMemberDef);
                addStatement(nodeMemberDef);
                return;
            }
        }

        ws();
        if (is('}')) {
            addErr(nodeMemberDef.colonPos, nodeMemberDef.colonPos, "expected node");
        } else {
            nodeMemberDef.value = parseNode();
        }
        setEnd(nodeMemberDef);
        addStatement(nodeMemberDef);
    }

    private void traitApplication(Syntax.Statement.Block parent) {
        int startPos = position();
        skip(); // '@'
        Syntax.Ident id = ident();
        var application = new Syntax.Statement.TraitApplication(parent, id);
        application.start = startPos;
        addStatement(application);

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
        return new Syntax.Ident(start, end, document.copySpan(start, end));
    }

    private void addErr(int start, int end, String message) {
        Syntax.Statement.Err err = new Syntax.Statement.Err(message);
        err.start = start;
        err.end = end;
        addError(err);
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
            case '\n', '\r', ' ', '\t' -> true;
            case ',' -> !isJson;
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
     * Used to halt parsing when we reach the end of the file,
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
