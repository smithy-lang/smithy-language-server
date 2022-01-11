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
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.utils.ListUtils;

public final class Completions {
    private static List<CompletionItem> keywordCompletions = Constants.KEYWORDS.stream()
            .map(kw -> createCompletion(kw, CompletionItemKind.Keyword)).collect(Collectors.toList());

    private static List<SmithyCompletionItem> keywordCompletions1 = Constants.KEYWORDS.stream()
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

            keywordCompletions1.forEach(kw -> {
                if (kw.getCompletionItem().getLabel().toLowerCase().startsWith(lcase)
                        && !comps.containsKey(kw.getCompletionItem().getLabel())) {
                    comps.put(kw.getCompletionItem().getLabel(), kw);
                }
            });
        }
        return ListUtils.copyOf(comps.values());
    }

    private static CompletionItem createCompletion(String s, CompletionItemKind kind) {
        CompletionItem ci = new CompletionItem(s);
        ci.setKind(kind);
        return ci;
    }

}
