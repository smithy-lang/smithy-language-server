/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static software.amazon.smithy.lsp.document.DocumentTest.safeString;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;
import software.amazon.smithy.lsp.project.SmithyFile;

public class DocumentSymbolTest {
    @Test
    public void documentSymbols() {
        String model = safeString("""
                $version: "2"
                namespace com.foo

                @trait
                string myTrait

                structure Foo {
                    @required
                    bar: Bar
                }

                structure Bar {
                    @myTrait("foo")
                    baz: Baz
                }

                @myTrait("abc")
                integer Baz
                """);
        List<String> names = getDocumentSymbolNames(model);

        assertThat(names, hasItems("myTrait", "Foo", "bar", "Bar", "baz", "Baz"));
    }

    private static List<String> getDocumentSymbolNames(String text) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectTest.load(workspace.getRoot());
        String uri = workspace.getUri("main.smithy");
        IdlFile idlFile = (IdlFile) (SmithyFile) project.getProjectFile(uri);

        List<String> names = new ArrayList<>();
        var handler = new DocumentSymbolHandler(idlFile.document(), idlFile.getParse().statements());
        for (var sym : handler.handle()) {
            names.add(sym.getRight().getName());
        }
        return names;
    }
}
