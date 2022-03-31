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

import org.eclipse.lsp4j.CompletionItem;
import org.junit.Test;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class CompletionTests {

    @Test
    public void resolveCurrentNamespace() throws Exception {
        String barNamespace = "namespace bar";

        String barContent = barNamespace + "\nstructure Hello{}\ninteger MyId2";
        String testContent = "namespace test\n@trait\nstructure Foo {}";
        Map<String, String> files = MapUtils.ofEntries(
            MapUtils.entry("bar/def1.smithy", barContent),
            MapUtils.entry("test/def2.smithy", testContent)
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyProject proj = hs.getProject();

            DocumentPreamble testPreamble = Document.detectPreamble(hs.readFile(hs.file("test/def2.smithy")));
            List<CompletionItem> itemsWithEdit = Completions.resolveImports(proj.getCompletions("Hello"), testPreamble);
            assertEquals("\nuse bar#Hello\n", itemsWithEdit.get(0).getAdditionalTextEdits().get(0).getNewText());

            DocumentPreamble barPreamble = Document.detectPreamble(hs.readFile(hs.file("bar/def1.smithy")));
            List<CompletionItem> itemsWithEdit2 = Completions.resolveImports(proj.getCompletions("Hello"), barPreamble);
            assertEquals(null, itemsWithEdit2.get(0).getAdditionalTextEdits());
        }
    }

    @Test
    public void multiFile() throws Exception {

        String def1 = "namespace test\nstring MyId";
        String def2 = "namespace test\nstructure Hello{}\ninteger MyId2";
        String def3 = "namespace test\n@trait\nstructure Foo {}";
        Map<String, String> files = MapUtils.ofEntries(MapUtils.entry("def1.smithy", def1),
                MapUtils.entry("bar/def2.smithy", def2), MapUtils.entry("foo/hello/def3.smithy", def3));

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyProject proj = hs.getProject();
            // Complete match
            assertEquals(SetUtils.of("Foo"), completeNames(proj, "Foo"));
            // Partial match
            assertEquals(SetUtils.of("MyId", "MyId2"), completeNames(proj, "MyI"));
            // Partial match (case insensitive)
            assertEquals(SetUtils.of("MyId", "MyId2"), completeNames(proj, "myi"));

            // no matches
            assertEquals(SetUtils.of(), completeNames(proj, "basdasdasdasd"));
            // empty token
            assertEquals(SetUtils.of(), completeNames(proj, ""));
            // built-in
            assertEquals(SetUtils.of("string", "String"), completeNames(proj, "Strin"));
            assertEquals(SetUtils.of("integer", "Integer"), completeNames(proj, "integer"));
            assertEquals(SetUtils.of("trait", "TraitShapeId", "TraitShapeIdList"), completeNames(proj, "trai"));

        }

    }

    public Set<String> completeNames(SmithyProject proj, String token) {
        return proj.getCompletions(token).stream().map(ci -> ci.getCompletionItem().getLabel())
                .collect(Collectors.toSet());
    }
}
