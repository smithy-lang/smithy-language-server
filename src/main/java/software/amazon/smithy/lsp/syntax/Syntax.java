/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.syntax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentImports;
import software.amazon.smithy.lsp.document.DocumentNamespace;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentVersion;

/**
 * Provides classes that represent the syntactic structure of a Smithy file, and
 * a means to parse Smithy files into those classes.
 * <p></p>
 * <h3>IDL Syntax</h3>
 * The result of a parse, {@link IdlParseResult}, is a list of {@link Statement},
 * rather than a syntax tree. For example, the following:
 * <code>
 *     \@someTrait
 *     structure Foo with [Bar] {
 *         \@otherTrait
 *         foo: String
 *     }
 * </code>
 * Produces the following list of statements:
 * <code>
 *     TraitApplication,
 *     ShapeDef,
 *     Mixins,
 *     Block,
 *     TraitApplication,
 *     MemberDef
 * </code>
 * While this sacrifices the ability to walk directly from the `foo` member def
 * to the `Foo` structure (or vice-versa), it simplifies error handling in the
 * parser by allowing more _nearly_ correct syntax, and localizes any errors as
 * close to their "cause" as possible. In general, the parser is as lenient as
 * possible, always producing a {@link Statement} for any given text, even if
 * the statement is incomplete or invalid. This means that consumers of the
 * parse result will always have _something_ they can analyze, despite the text
 * having invalid syntax, so the server stays responsive as you type.
 *
 * <p>At a high-level, the design decisions of the parser and {@link Statement}
 * are guided by the following ideas:
 * - Minimal lookahead or structural validation to be as fast as possible.
 * - Minimal memory allocations, for intermediate objects and the parse result.
 * - Minimal sensitivity to context, leaving the door open to easily implement
 *   incremental/partial re-parsing of changes if it becomes necessary.
 * - Provide strongly-typed, concrete syntax productions so consumers don't need
 *   to create their own wrappers.
 *
 * <p>There are a few things to note about the public API of {@link Statement}s
 * produced by the parser.
 * - Any `final` field is definitely assigned, whereas any non `final` field
 *   may be null (other than {@link Statement#start} and {@link Statement#end},
 *   which are definitely assigned).
 * <p></p>
 * <h3>Node Syntax</h3>
 * This class also provides classes for the JSON-like Smithy Node, which can
 * be used standalone (see {@link Syntax#parseNode(Document)}). {@link Node}
 * is a more typical recursive parse tree, so parsing produces a single
 * {@link Node}, and any given {@link Node} may be a {@link Node.Err}. Like
 * {@link Statement}, the parser tries to be as lenient as possible here too.
 */
public final class Syntax {
    private Syntax() {
    }

    /**
     * Wrapper for {@link Statement.ForResource} and {@link Statement.Mixins},
     * which often are used together.
     *
     * @param forResource The nullable for-resource statement.
     * @param mixins The nullable mixins statement.
     */
    public record ForResourceAndMixins(Statement.ForResource forResource, Statement.Mixins mixins) {}

    /**
     * The result of parsing an IDL document, containing some extra computed
     * info that is used often.
     *
     * @param statements The parsed statements.
     * @param errors The errors that occurred during parsing.
     * @param version The IDL version that was parsed.
     * @param namespace The namespace that was parsed
     * @param imports The imports that were parsed.
     */
    public record IdlParseResult(
            List<Statement> statements,
            List<Err> errors,
            DocumentVersion version,
            DocumentNamespace namespace,
            DocumentImports imports
    ) {}

    /**
     * @param document The document to parse.
     * @return The IDL parse result.
     */
    public static IdlParseResult parseIdl(Document document) {
        Parser parser =  Parser.forIdl(document);
        parser.parseIdl();
        List<Statement> statements = parser.statements;
        DocumentParser documentParser = DocumentParser.forStatements(document, statements);
        return new IdlParseResult(
                statements,
                parser.errors,
                documentParser.documentVersion(),
                documentParser.documentNamespace(),
                documentParser.documentImports());
    }

    /**
     * The result of parsing a Node document.
     *
     * @param value The parsed node.
     * @param errors The errors that occurred during parsing.
     */
    public record NodeParseResult(Node value, List<Err> errors) {}

    /**
     * @param document The document to parse.
     * @return The Node parse result.
     */
    public static NodeParseResult parseNode(Document document) {
        Parser parser = Parser.forJson(document);
        Node node = parser.parseNode();
        return new NodeParseResult(node, parser.errors);
    }

    /**
     * Any syntactic construct has this base type. Mostly used to share
     * {@link #start()} and {@link #end()} that all items have.
     */
    public abstract static sealed class Item {
        int start;
        int end;

        public final int start() {
            return start;
        }

        public final int end() {
            return end;
        }

        /**
         * @param pos The character offset in a file to check
         * @return Whether {@code pos} is within this item
         */
        public final boolean isIn(int pos) {
            return start <= pos && end > pos;
        }
    }

    /**
     * Common type of all JSON-like node syntax productions.
     */
    public abstract static sealed class Node extends Item {
        /**
         * @return The type of the node.
         */
        public final Type type() {
            return switch (this) {
                case Kvps ignored -> Type.Kvps;
                case Kvp ignored -> Type.Kvp;
                case Obj ignored -> Type.Obj;
                case Arr ignored -> Type.Arr;
                case Ident ignored -> Type.Ident;
                case Str ignored -> Type.Str;
                case Num ignored -> Type.Num;
                case Err ignored -> Type.Err;
            };
        }

        /**
         * Applies this node to {@code consumer}, and traverses this node in
         * depth-first order.
         *
         * @param consumer Consumer to do something with each node.
         */
        public final void consume(Consumer<Node> consumer) {
            consumer.accept(this);
            switch (this) {
                case Kvps kvps -> kvps.kvps().forEach(kvp -> kvp.consume(consumer));
                case Kvp kvp -> {
                    kvp.key.consume(consumer);
                    if (kvp.value != null) {
                        kvp.value.consume(consumer);
                    }
                }
                case Obj obj -> obj.kvps.consume(consumer);
                case Arr arr -> arr.elements.forEach(elem -> elem.consume(consumer));
                default -> {
                }
            }
        }

        public enum Type {
            Kvps,
            Kvp,
            Obj,
            Arr,
            Str,
            Num,
            Ident,
            Err
        }

        /**
         * A list of key-value pairs. May be within an {@link Obj}, or standalone
         * (like in a trait body).
         */
        public static final class Kvps extends Node {
            private final List<Kvp> kvps = new ArrayList<>();

            void add(Kvp kvp) {
                kvps.add(kvp);
            }

            public List<Kvp> kvps() {
                return kvps;
            }
        }

        /**
         * A single key-value pair. {@link #key} will definitely be present,
         * while {@link #value} may be null.
         */
        public static final class Kvp extends Node {
            final Str key;
            int colonPos = -1;
            Node value;

            Kvp(Str key) {
                this.key = key;
            }

            public Str key() {
                return key;
            }

            public Node value() {
                return value;
            }

            /**
             * @param pos The character offset to check
             * @return Whether the given offset is within the value of this pair
             */
            public boolean inValue(int pos) {
                if (colonPos < 0) {
                    return false;
                } else if (value == null) {
                    return pos > colonPos && pos < end;
                } else {
                    return value.isIn(pos);
                }
            }
        }

        /**
         * Wrapper around {@link Kvps}, for objects enclosed in {}.
         */
        public static final class Obj extends Node {
            final Kvps kvps = new Kvps();

            public Kvps kvps() {
                return kvps;
            }
        }

        /**
         * An array of {@link Node}.
         */
        public static final class Arr extends Node {
            final List<Node> elements = new ArrayList<>();

            public List<Node> elements() {
                return elements;
            }
        }

        /**
         * A string value. The Smithy {@link Node}s can also be regular
         * identifiers, so this class a single subclass {@link Ident}.
         */
        public static sealed class Str extends Node {
            final int lineNumber;
            final String value;

            Str(int lineNumber, int start, int end, String value) {
                this.lineNumber = lineNumber;
                this.start = start;
                this.end = end;
                this.value = value;
            }

            public int lineNumber() {
                return lineNumber;
            }

            public String stringValue() {
                return value;
            }
        }

        /**
         * A numeric value.
         */
        public static final class Num extends Node {
            final BigDecimal value;

            Num(BigDecimal value) {
                this.value = value;
            }

            public BigDecimal value() {
                return value;
            }
        }

        /**
         * An error representing an invalid {@link Node} value.
         */
        public static final class Err extends Node implements Syntax.Err {
            final String message;

            Err(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }
    }

    /**
     * Common type of all IDL syntax productions.
     */
    public abstract static sealed class Statement extends Item {
        /**
         * @return The type of the statement.
         */
        public final Type type() {
            return switch (this) {
                case Incomplete ignored -> Type.Incomplete;
                case Control ignored -> Type.Control;
                case Metadata ignored -> Type.Metadata;
                case Namespace ignored -> Type.Namespace;
                case Use ignored -> Type.Use;
                case Apply ignored -> Type.Apply;
                case ShapeDef ignored -> Type.ShapeDef;
                case ForResource ignored -> Type.ForResource;
                case Mixins ignored -> Type.Mixins;
                case TraitApplication ignored -> Type.TraitApplication;
                case MemberDef ignored -> Type.MemberDef;
                case EnumMemberDef ignored -> Type.EnumMemberDef;
                case ElidedMemberDef ignored -> Type.ElidedMemberDef;
                case InlineMemberDef ignored -> Type.InlineMemberDef;
                case NodeMemberDef ignored -> Type.NodeMemberDef;
                case Block ignored -> Type.Block;
                case Err ignored -> Type.Err;
            };
        }

        /**
         * @param pos The character offset in the file to check
         * @return Whether {@code pos} is within the keyword at the start
         *  of this statement. Always returns {@code false} if this
         *  statement doesn't start with a keyword.
         */
        public boolean isInKeyword(int pos) {
            return false;
        }

        public enum Type {
            Incomplete,
            Control,
            Metadata,
            Namespace,
            Use,
            Apply,
            ShapeDef,
            ForResource,
            Mixins,
            TraitApplication,
            MemberDef,
            EnumMemberDef,
            ElidedMemberDef,
            InlineMemberDef,
            NodeMemberDef,
            Block,
            Err;
        }

        /**
         * A single identifier that can't be associated with an actual statement.
         * For example, `stru` by itself is an incomplete statement.
         */
        public static final class Incomplete extends Statement {
            final Ident ident;

            Incomplete(Ident ident) {
                this.ident = ident;
            }

            public Ident ident() {
                return ident;
            }
        }

        /**
         * A control statement.
         */
        public static final class Control extends Statement {
            final Ident key;
            Node value;

            Control(Ident key) {
                this.key = key;
            }

            public Ident key() {
                return key;
            }

            public Node value() {
                return value;
            }
        }

        /**
         * A metadata statement.
         */
        public static final class Metadata extends Statement {
            final Ident key;
            Node value;

            Metadata(Ident key) {
                this.key = key;
            }

            public Ident key() {
                return key;
            }

            public Node value() {
                return value;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return pos >= start && pos < start + "metadata".length();
            }
        }

        /**
         * A namespace statement, i.e. `namespace` followed by an identifier.
         */
        public static final class Namespace extends Statement {
            final Ident namespace;

            Namespace(Ident namespace) {
                this.namespace = namespace;
            }

            public Ident namespace() {
                return namespace;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return pos >= start && pos < start + "namespace".length();
            }
        }

        /**
         * A use statement, i.e. `use` followed by an identifier.
         */
        public static final class Use extends Statement {
            final Ident use;

            Use(Ident use) {
                this.use = use;
            }

            public Ident use() {
                return use;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return pos >= start && pos < start + "use".length();
            }
        }

        /**
         * An apply statement, i.e. `apply` followed by an identifier. Doesn't
         * include, require, or care about subsequent trait applications.
         */
        public static final class Apply extends Statement {
            final Ident id;

            Apply(Ident id) {
                this.id = id;
            }

            public Ident id() {
                return id;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return pos >= start && pos < start + "apply".length();
            }
        }

        /**
         * A shape definition, i.e. a shape type followed by an identifier.
         */
        public static final class ShapeDef extends Statement {
            final Ident shapeType;
            final Ident shapeName;

            ShapeDef(Ident shapeType, Ident shapeName) {
                this.shapeType = shapeType;
                this.shapeName = shapeName;
            }

            public Ident shapeType() {
                return shapeType;
            }

            public Ident shapeName() {
                return shapeName;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return shapeType.isIn(pos);
            }
        }

        /**
         * `for` followed by an identifier. Only appears after a {@link ShapeDef}
         * or after an {@link InlineMemberDef}.
         */
        public static final class ForResource extends Statement {
            final Ident resource;

            ForResource(Ident resource) {
                this.resource = resource;
            }

            public Ident resource() {
                return resource;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return pos >= start && pos < start + "for".length();
            }
        }

        /**
         * `with` followed by an array. The array may not be present in text,
         * but it is in this production. Only appears after a {@link ShapeDef},
         * {@link InlineMemberDef}, or {@link ForResource}.
         */
        public static final class Mixins extends Statement {
            final List<Ident> mixins = new ArrayList<>();

            public List<Ident> mixins() {
                return mixins;
            }

            @Override
            public boolean isInKeyword(int pos) {
                return pos >= start && pos < start + "with".length();
            }
        }

        /**
         * Common type of productions that can appear within shape bodies, i.e.
         * within a {@link Block}.
         *
         * <p>The sole purpose of this class is to make it cheap to navigate
         * from a statement to the {@link Block} it resides within when
         * searching for the statement corresponding to a given character offset
         * in a document.</p>
         */
        abstract static sealed class MemberStatement extends Statement {
            final Block parent;

            protected MemberStatement(Block parent) {
                this.parent = parent;
            }

            /**
             * @return The possibly null block enclosing this statement.
             */
            public Block parent() {
                return parent;
            }
        }

        /**
         * A trait application, i.e. `@` followed by an identifier.
         */
        public static final class TraitApplication extends MemberStatement {
            final Ident id;
            Node value;

            TraitApplication(Block parent, Ident id) {
                super(parent);
                this.id = id;
            }

            public Ident id() {
                return id;
            }

            public Node value() {
                return value;
            }
        }

        /**
         * A member definition, i.e. identifier `:` identifier. Only appears
         * in {@link Block}s.
         */
        public static final class MemberDef extends MemberStatement {
            final Ident name;
            int colonPos = -1;
            Ident target;

            MemberDef(Block parent, Ident name) {
                super(parent);
                this.name = name;
            }

            public Ident name() {
                return name;
            }

            public Ident target() {
                return target;
            }

            /**
             * @param pos The character offset to check
             * @return Whether the given offset is within this member's target
             */
            public boolean inTarget(int pos) {
                if (colonPos < 0) {
                    return false;
                } else if (target == null || target.isEmpty()) {
                    return pos > colonPos;
                } else {
                    return target.isIn(pos);
                }
            }
        }

        /**
         * An enum member definition, i.e. an identifier followed by an optional
         * value assignment. Only appears in {@link Block}s.
         */
        public static final class EnumMemberDef extends MemberStatement {
            final Ident name;
            Node value;

            EnumMemberDef(Block parent, Ident name) {
                super(parent);
                this.name = name;
            }

            public Ident name() {
                return name;
            }
        }

        /**
         * An elided member definition, i.e. `$` followed by an identifier. Only
         * appears in {@link Block}s.
         */
        public static final class ElidedMemberDef extends MemberStatement {
            final Ident name;

            ElidedMemberDef(Block parent, Ident name) {
                super(parent);
                this.name = name;
            }

            public Ident name() {
                return name;
            }
        }

        /**
         * An inline member definition, i.e. an identifier followed by `:=`. Only
         * appears in {@link Block}s, and doesn't include the actual definition,
         * just the member name.
         */
        public static final class InlineMemberDef extends MemberStatement {
            final Ident name;

            InlineMemberDef(Block parent, Ident name) {
                super(parent);
                this.name = name;
            }

            public Ident name() {
                return name;
            }
        }

        /**
         * A member definition with a node value, i.e. identifier `:` node value.
         * Only appears in {@link Block}s.
         */
        public static final class NodeMemberDef extends MemberStatement {
            final Ident name;
            int colonPos = -1;
            Node value;

            NodeMemberDef(Block parent, Ident name) {
                super(parent);
                this.name = name;
            }

            public Ident name() {
                return name;
            }

            public Node value() {
                return value;
            }

            /**
             * @param pos The character offset to check
             * @return Whether the given {@code pos} is within this member's value
             */
            public boolean inValue(int pos) {
                return (value != null && value.isIn(pos))
                        || (colonPos >= 0 && pos > colonPos);
            }
        }

        /**
         * Used to indicate the start of a block, i.e. {}.
         */
        public static final class Block extends MemberStatement {
            final int statementIndex;
            int lastStatementIndex;

            Block(Block parent, int lastStatementIndex) {
                super(parent);
                this.statementIndex = lastStatementIndex;
                this.lastStatementIndex = lastStatementIndex;
            }

            public int statementIndex() {
                return statementIndex;
            }

            public int lastStatementIndex() {
                return lastStatementIndex;
            }
        }

        /**
         * An error that occurred during IDL parsing. This is distinct from
         * {@link Node.Err} primarily because {@link Node.Err} is an actual
         * value a {@link Node} can have.
         */
        public static final class Err extends Statement implements Syntax.Err {
            final String message;

            Err(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }
    }

    /**
     * An identifier in a {@link Node} or {@link Statement}. Starts with any
     * alpha or `_` character, followed by any sequence of Shape ID characters
     * (i.e. `.`, `#`, `$`, `_` digits, alphas).
     */
    public static final class Ident extends Node.Str {
        static final Ident EMPTY = new Ident(-1, -1, -1, "");

        Ident(int lineNumber, int start, int end, String value) {
            super(lineNumber, start, end, value);
        }

        public boolean isEmpty() {
            return (start - end) == 0;
        }
    }

    /**
     * Represents any syntax error, either {@link Node} or {@link Statement}.
     */
    public sealed interface Err {
        /**
         * @return The start index of the error.
         */
        int start();

        /**
         * @return The end index of the error.
         */
        int end();

        /**
         * @return The error message.
         */
        String message();
    }
}
