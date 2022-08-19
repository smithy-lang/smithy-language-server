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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.Test;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

public class CompletionsTest {

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
            List<CompletionItem> itemsWithEdit = Completions.resolveImports(proj.getCompletions("Hello", false, Optional.empty()),
                    testPreamble);
            assertEquals("\nuse bar#Hello\n", itemsWithEdit.get(0).getAdditionalTextEdits().get(0).getNewText());

            DocumentPreamble barPreamble = Document.detectPreamble(hs.readFile(hs.file("bar/def1.smithy")));
            List<CompletionItem> itemsWithEdit2 = Completions.resolveImports(proj.getCompletions("Hello", false, Optional.empty()),
                    barPreamble);
            assertNull(itemsWithEdit2.get(0).getAdditionalTextEdits());
        }
    }

    @Test
    public void multiFileV1() throws Exception {
        Path baseDir = Paths.get(Completions.class.getResource("models/v1").toURI());
        Path traitDefModel = baseDir.resolve("trait-def.smithy");
        String traitDef = IoUtils.readUtf8File(traitDefModel);

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry("def1.smithy", "namespace test\nstring MyId"),
                MapUtils.entry("bar/def2.smithy", "namespace test\nstructure Hello{}\ninteger MyId2"),
                MapUtils.entry("foo/hello/def3.smithy", "namespace test\n@test()\n@trait\nstructure Foo {}"),
                MapUtils.entry("foo/hello/def4.smithy", "namespace test\n@http()\noperation Bar{}"),
                MapUtils.entry("trait-def.smithy", traitDef)
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyProject proj = hs.getProject();
            // Complete match
            assertEquals(SetUtils.of("Foo"), completeNames(proj, "Foo", false));
            // Partial match
            assertEquals(SetUtils.of("MyId", "MyId2"), completeNames(proj, "MyI", false));
            // Partial match (case insensitive)
            assertEquals(SetUtils.of("MyId", "MyId2"), completeNames(proj, "myi", false));

            // no matches
            assertEquals(SetUtils.of(), completeNames(proj, "basdasdasdasd", false));
            // empty token
            assertEquals(SetUtils.of(), completeNames(proj, "", false));
            // built-in
            assertEquals(SetUtils.of("string", "String"), completeNames(proj, "Strin", false));
            assertEquals(SetUtils.of("integer", "Integer"), completeNames(proj, "intege", false));
            // Structure trait with zero required members and default.
            assertEquals(SetUtils.of("trait", "trait()"), completeNames(proj, "trai", true, "test#Foo"));
            // Completions for each supported node value type.
            assertEquals(SetUtils.of("test(blob: \"\", bool: true|false, short: , integer: , long: , float: ," +
                            " double: , bigDecimal: , bigInteger: , string: \"\", timestamp: \"\", list: []," +
                            " set: [], map: {}, struct: {nested: {nestedMember: \"\"}}, union: {})", "test()"),
                    completeNames(proj, "test", true, "test#Foo"));
            // Limit completions to traits that can be applied to target shape.
            // Other http* traits cannot apply to an operation.
            assertEquals(SetUtils.of("http(method: \"\", uri: \"\")", "http()", "httpChecksumRequired"),
                    completeNames(proj, "htt", true, "test#Bar"));

        }
    }

    @Test
    public void multiFileV2() throws Exception {
        Path baseDir = Paths.get(Completions.class.getResource("models/v2").toURI());
        Path traitDefModel = baseDir.resolve("trait-def.smithy");
        String traitDef = IoUtils.readUtf8File(traitDefModel);

        Map<String, String> files = MapUtils.ofEntries(
                MapUtils.entry("def1.smithy", "namespace test\nstring MyId"),
                MapUtils.entry("bar/def2.smithy", "namespace test\nstructure Hello{}\ninteger MyId2"),
                MapUtils.entry("foo/hello/def3.smithy", "namespace test\n@test()\n@trait\nstructure Foo {}"),
                MapUtils.entry("foo/hello/def4.smithy", "namespace test\n@http()\noperation Bar{}"),
                MapUtils.entry("trait-def.smithy", traitDef)
        );

        try (Harness hs = Harness.create(SmithyBuildExtensions.builder().build(), files)) {
            SmithyProject proj = hs.getProject();
            // Complete match
            assertEquals(SetUtils.of("Foo"), completeNames(proj, "Foo", false));
            // Partial match
            assertEquals(SetUtils.of("MyId", "MyId2"), completeNames(proj, "MyI", false));
            // Partial match (case insensitive)
            assertEquals(SetUtils.of("MyId", "MyId2"), completeNames(proj, "myi", false));

            // no matches
            assertEquals(SetUtils.of(), completeNames(proj, "basdasdasdasd", false));
            // empty token
            assertEquals(SetUtils.of(), completeNames(proj, "", false));
            // built-in
            assertEquals(SetUtils.of("string", "String"), completeNames(proj, "Strin", false));
            assertEquals(SetUtils.of("integer", "Integer"), completeNames(proj, "intege", false));
            // Structure trait with zero required members and default.
            assertEquals(SetUtils.of("trait", "trait()"), completeNames(proj, "trai", true, "test#Foo"));
            // Completions for each supported node value type.
            assertEquals(SetUtils.of("test(blob: \"\", bool: true|false, short: , integer: , long: , float: ," +
                            " double: , bigDecimal: , bigInteger: , string: \"\", timestamp: \"\", list: []," +
                            " map: {}, struct: {nested: {nestedMember: \"\"}}, union: {})", "test()"),
                    completeNames(proj, "test", true, "test#Foo"));
            // Limit completions to traits that can be applied to target shape.
            // Other http* traits cannot apply to an operation.
            assertEquals(SetUtils.of("http(method: \"\", uri: \"\")", "http()", "httpChecksumRequired"),
                    completeNames(proj, "htt", true, "test#Bar"));

        }
    }

    Set<String> completeNames(SmithyProject proj, String token, boolean isTrait) {
        return completeNames(proj, token, isTrait, null);
    }

    Set<String> completeNames(SmithyProject proj, String token, boolean isTrait, String shapeId) {
        Optional<ShapeId> target = Optional.empty();
        if (shapeId != null) {
            target = Optional.of(ShapeId.from(shapeId));
        }
        return proj.getCompletions(token, isTrait, target).stream().map(ci -> ci.getCompletionItem().getLabel())
                .collect(Collectors.toSet());
    }
}
