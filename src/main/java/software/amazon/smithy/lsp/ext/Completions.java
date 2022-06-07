/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp.ext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.TextEdit;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.utils.ListUtils;

public final class Completions {
    private static final List<SmithyCompletionItem> KEYWORD_COMPLETIONS = Constants.KEYWORDS.stream()
            .map(kw -> new SmithyCompletionItem(createCompletion(kw, CompletionItemKind.Keyword)))
            .collect(Collectors.toList());

    private Completions() {
    }

    /**
     * From a model and (potentially partial) token, build a list of completions.
     * Empty list is returned for empty tokens. Current implementation is prefix
     * based.
     *
     * @param model Smithy model
     * @param token token
     * @param isTraitShapeId boolean
     * @param target Optional ShapeId of the target trait target
     * @return list of completion items
     */
    public static List<SmithyCompletionItem> find(Model model, String token, boolean isTraitShapeId,
                                                  Optional<ShapeId> target) {
        Map<String, SmithyCompletionItem> comps = new HashMap<>();
        String lcase = token.toLowerCase();

        Set<ShapeId> shapeIdSet;
        // If the token is part of a trait shapeId, filter the set to trait shapes which can be applied to the shape
        // that the trait targets.
        if (isTraitShapeId) {
            shapeIdSet = getTraitShapeIdSet(model, target);
        } else {
            // Otherwise, use all shapes in model the as potential completions.
            shapeIdSet = model.getShapeIds();
        }

        if (!token.trim().isEmpty()) {
            shapeIdSet.forEach(shapeId -> {
                if (shapeId.getName().toLowerCase().startsWith(lcase) && !comps.containsKey(shapeId.getName())) {
                    String name = shapeId.getName();
                    String namespace = shapeId.getNamespace();
                    if (isTraitShapeId) {
                        Shape shape = model.expectShape(shapeId);
                        List<CompletionItem> completions = createTraitCompletions(shape, model,
                                CompletionItemKind.Class);
                        for (CompletionItem item : completions) {
                            // Use the label to merge traits without required members and the default version.
                            comps.put(item.getLabel(), smithyCompletionItem(item, namespace, name));
                        }
                    } else {
                        CompletionItem completionItem = createCompletion(name, CompletionItemKind.Class);
                        comps.put(name, smithyCompletionItem(completionItem, namespace, name));
                    }
                }
            });
            KEYWORD_COMPLETIONS.forEach(kw -> {
                if (!isTraitShapeId && kw.getCompletionItem().getLabel().toLowerCase().startsWith(lcase)
                        && !comps.containsKey(kw.getCompletionItem().getLabel())) {
                    comps.put(kw.getCompletionItem().getLabel(), kw);
                }
            });
        }
        return ListUtils.copyOf(comps.values());
    }

    /**
     * For a given list of completion items and a live document preamble, create a list
     * of completion items with necessary text edits to support auto-imports.
     *
     * @param items    list of model-specific completion items
     * @param preamble live document preamble
     * @return list of completion items (optionally with text edits)
     */
    public static List<CompletionItem> resolveImports(List<SmithyCompletionItem> items, DocumentPreamble preamble) {
        return items.stream().map(sci -> {
            CompletionItem result = sci.getCompletionItem();
            Optional<String> qualifiedImport = sci.getQualifiedImport();
            Optional<String> importNamespace = sci.getImportNamespace();
            Optional<String> currentNamespace = preamble.getCurrentNamespace();


            qualifiedImport.ifPresent(qi -> {
                boolean matchesCurrent = importNamespace.equals(currentNamespace);
                boolean matchesPrelude = importNamespace.equals(Optional.of(Constants.SMITHY_PRELUDE_NAMESPACE));
                boolean shouldImport = !preamble.hasImport(qi) && !matchesPrelude && !matchesCurrent;

                if (shouldImport) {
                    TextEdit te = Document.insertPreambleLine("use " + qualifiedImport.get(), preamble);
                    result.setAdditionalTextEdits(ListUtils.of(te));
                }
            });

            return result;
        }).collect(Collectors.toList());
    }

    // Get set of trait shapes from model that can be applied to an optional shapeId.
    private static Set<ShapeId> getTraitShapeIdSet(Model model, Optional<ShapeId> target) {
        return model.shapes()
                .filter(shape -> shape.hasTrait(ShapeId.from("smithy.api#trait")))
                .filter(shape -> {
                    if (!target.isPresent()) {
                        return true;
                    }
                    return shape.expectTrait(TraitDefinition.class).getSelector().shapes(model)
                            .anyMatch(matchingShape -> matchingShape.getId().equals(target.get()));
                })
                .map(shape -> shape.getId())
                .collect(Collectors.toSet());
    }

    private static CompletionItem createCompletion(String s, CompletionItemKind kind) {
        CompletionItem ci = new CompletionItem(s);
        ci.setKind(kind);
        return ci;
    }

    private static SmithyCompletionItem smithyCompletionItem(CompletionItem item, String namespace, String name) {
        return new SmithyCompletionItem(item, namespace, name);
    }

    private static List<CompletionItem> createTraitCompletions(Shape shape, Model model, CompletionItemKind kind) {
        List<CompletionItem> completions = new ArrayList<>();
        completions.add(createTraitCompletion(shape, model, kind));
        // Add a default completion for structure shapes with members.
        if (shape.isStructureShape() && !shape.members().isEmpty()) {
            if (shape.members().stream().anyMatch(member -> member.hasTrait(RequiredTrait.class))) {
                // If the structure has required members, add a default with empty parens.
                completions.add(createCompletion(shape.getId().getName() + "()", kind));
            } else {
                // Otherwise, add a completion without any parens.
                completions.add(createCompletion(shape.getId().getName(), kind));
            }

        }
        return completions;
    }

    private static CompletionItem createTraitCompletion(Shape shape, Model model, CompletionItemKind kind) {
        String traitBody = shape.accept(new TraitBodyVisitor(model));
        // Strip outside pair of brackets from any structure traits.
        if (traitBody.charAt(0) == '{') {
            traitBody = traitBody.substring(1, traitBody.length() - 1);
        }
        if (shape.members().isEmpty()) {
            return createCompletion(shape.getId().getName(), kind);
        }
        return createCompletion(shape.getId().getName() + "(" + traitBody + ")", kind);
    }

    private static final class TraitBodyVisitor extends ShapeVisitor.Default<String> {
        private final Model model;

        TraitBodyVisitor(Model model) {
            this.model = model;
        }

        @Override
        protected String getDefault(Shape shape) {
            return "";
        }

        @Override
        public String bigDecimalShape(BigDecimalShape shape) {
            return "\"\"";
        }

        @Override
        public String bigIntegerShape(BigIntegerShape shape) {
            return "\"\"";
        }

        @Override
        public String blobShape(BlobShape shape) {
            return "\"\"";
        }

        @Override
        public String booleanShape(BooleanShape shape) {
            return "true|false";
        }

        @Override
        public String doubleShape(DoubleShape shape) {
            return "\"\"";
        }

        @Override
        public String floatShape(FloatShape shape) {
            return "\"\"";
        }

        @Override
        public String listShape(ListShape shape) {
            return "[]";
        }

        @Override
        public String mapShape(MapShape shape) {
            return "{}";
        }

        @Override
        public String setShape(SetShape shape) {
            return "[]";
        }

        @Override
        public String stringShape(StringShape shape) {
            return "\"\"";
        }

        @Override
        public String structureShape(StructureShape shape) {
            List<String> entries = new ArrayList<>();
            for (MemberShape memberShape : shape.members()) {
                if (memberShape.hasTrait(RequiredTrait.class)) {
                    Shape targetShape = model.expectShape(memberShape.getTarget());
                    entries.add(memberShape.getMemberName() + ": " + targetShape.accept(this));
                }
            }
            return "{" + String.join(", ", entries) + "}";
        }

        @Override
        public String timestampShape(TimestampShape shape) {
            return "\"\"";
        }

        @Override
        public String unionShape(UnionShape shape) {
            return "{}";
        }
    }
}
