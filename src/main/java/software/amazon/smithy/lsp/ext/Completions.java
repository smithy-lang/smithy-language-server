/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.TextEdit;
import software.amazon.smithy.model.Model;
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
     * @return list of completion items
     */
    public static List<SmithyCompletionItem> find(Model model, String token) {
        Map<String, SmithyCompletionItem> comps = new HashMap();
        String lcase = token.toLowerCase();

        if (!token.trim().isEmpty()) {
            model.getShapeIds().forEach(shapeId -> {
                if (shapeId.getName().toLowerCase().startsWith(lcase) && !comps.containsKey(shapeId.getName())) {
                    CompletionItem completionItem = createCompletion(shapeId.getName(), CompletionItemKind.Class);

                    comps.put(shapeId.getName(),
                            new SmithyCompletionItem(completionItem, shapeId.getNamespace(), shapeId.getName()));
                }
            });

            KEYWORD_COMPLETIONS.forEach(kw -> {
                if (kw.getCompletionItem().getLabel().toLowerCase().startsWith(lcase)
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

            boolean shouldImport = qualifiedImport.isPresent()
                    && !preamble.hasImport(qualifiedImport.get())
                    && !importNamespace.equals(Optional.of(Constants.SMITHY_PRELUDE_NAMESPACE));

            if (shouldImport) {
                TextEdit te = Document.insertPreambleLine("use " + qualifiedImport.get(), preamble);
                result.setAdditionalTextEdits(ListUtils.of(te));
            }

            return result;
        }).collect(Collectors.toList());
    }

    private static CompletionItem createCompletion(String s, CompletionItemKind kind) {
        CompletionItem ci = new CompletionItem(s);
        ci.setKind(kind);
        return ci;
    }

}
