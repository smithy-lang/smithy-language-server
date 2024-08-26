/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.document.syntax;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.Document;

/**
 * Provides classes that represent the syntactic structure of a Smithy file, and
 * a means to parse Smithy files into those classes.
 * <p></p>
 * <h3>IDL Syntax</h3>
 * The result of a parse, {@link Syntax.IdlParse}, is a list of {@link Statement},
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
 * <p>There are a few things to note about the API of {@link Statement}s
 * produced by the parser.
 * - Any `final` field is definitely assigned, whereas any non `final` field
 *   may be null (other than {@link Statement#start} and {@link Statement#end},
 *   which are definitely assigned).
 * - Concrete text is not stored in {@link Statement}s. Instead,
 *   {@link Statement#start} and {@link Statement#end} can be used to copy a
 *   value from the underlying document as needed. This is done to reduce the
 *   memory footprint of parsing.
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

    public record IdlParse(List<Statement> statements, List<Err> errors) {}

    public record NodeParse(Node value, List<Err> errors) {}

    /**
     * @param document The document to parse
     * @return The IDL parse result
     */
    public static IdlParse parseIdl(Document document) {
        Parser parser = new Parser(document);
        parser.parseIdl();
        return new IdlParse(parser.statements, parser.errors);
    }

    /**
     * @param document The document to parse
     * @return The Node parse result
     */
    public static NodeParse parseNode(Document document) {
        Parser parser = new Parser(document);
        Node node = parser.parseNode();
        return new NodeParse(node, parser.errors);
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

        /**
         * @param document The document to get the range in
         * @return The range of this item in the given {@code document}
         */
        public final Range rangeIn(Document document) {
            return document.rangeBetween(start, end);
        }
    }

    /**
     * Common type that all JSON-like node syntax productions share.
     */
    public abstract static sealed class Node extends Item {
        /**
         * @return The type of the node
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
                case Kvps kvps -> kvps.kvps.forEach(kvp -> kvp.consume(consumer));
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
            final List<Kvp> kvps = new ArrayList<>();

            void add(Kvp kvp) {
                kvps.add(kvp);
            }
        }

        /**
         * A single key-value pair. {@link #key} will definitely be present,
         * while {@link #value} may be null.
         */
        public static final class Kvp extends Node {
            final Str key;
            Node value;

            Kvp(Str key) {
                this.key = key;
            }
        }

        /**
         * Wrapper around {@link Kvps}, for objects enclosed in {}.
         */
        public static final class Obj extends Node {
            final Kvps kvps = new Kvps();
        }

        /**
         * An array of {@link Node}.
         */
        public static final class Arr extends Node {
            final List<Node> elements = new ArrayList<>();
        }

        /**
         * A string value. The Smithy {@link Node}s can also be regular
         * identifiers, so this class a single subclass {@link Ident}.
         */
        public static sealed class Str extends Node {
            /**
             * @param document Document to copy the string value from
             * @return The literal string value, excluding enclosing ""
             */
            public String copyValueFrom(Document document) {
                return document.copySpan(start + 1, end - 1); // Don't include the '"'s
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
         * @return The type of the statement
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

        public enum Type {
            Incomplete,
            Control,
            Metadata,
            Namespace,
            Use,
            Apply,
            ShapeNode,
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
        }

        /**
         * `with` followed by an array. The array may not be present in text,
         * but it is in this production. Only appears after a {@link ShapeDef},
         * {@link InlineMemberDef}, or {@link ForResource}.
         */
        public static final class Mixins extends Statement {
            final List<Ident> mixins = new ArrayList<>();
        }

        /**
         * A trait application, i.e. `@` followed by an identifier.
         */
        public static final class TraitApplication extends Statement {
            final Ident id;
            Node value;

            TraitApplication(Ident id) {
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
        public static final class MemberDef extends Statement {
            final Ident name;
            int colonPos = -1;
            Ident target;

            MemberDef(Ident name) {
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
        public static final class EnumMemberDef extends Statement {
            final Ident name;
            Node value;

            EnumMemberDef(Ident name) {
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
        public static final class ElidedMemberDef extends Statement {
            final Ident name;

            ElidedMemberDef(Ident name) {
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
        public static final class InlineMemberDef extends Statement {
            final Ident name;

            InlineMemberDef(Ident name) {
                this.name = name;
            }
        }

        /**
         * A member definition with a node value, i.e. identifier `:` node value.
         * Only appears in {@link Block}s.
         */
        public static final class NodeMemberDef extends Statement {
            final Ident name;
            int colonPos = -1;
            Node value;

            NodeMemberDef(Ident name) {
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
        public static final class Block extends Statement {
            int lastStatementIndex;

            Block(int lastStatementIndex) {
                this.lastStatementIndex = lastStatementIndex;
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
        static final Ident EMPTY = new Ident(-1, -1);

        Ident(int start, int end) {
            this.start = start;
            this.end = end;
        }

        public boolean isEmpty() {
            return (start - end) == 0;
        }

        @Override
        public String copyValueFrom(Document document) {
            if (start < 0 && end < 0) {
                return "";
            }
            return document.copySpan(start, end); // There's no '"'s here
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
