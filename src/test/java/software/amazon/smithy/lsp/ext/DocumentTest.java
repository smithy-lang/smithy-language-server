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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.Position;
import org.junit.Test;
import software.amazon.smithy.utils.ListUtils;

public class DocumentTest {

    @Test
    public void detectPreambleV1() throws Exception {
        Path baseDir = Paths.get(Document.class.getResource("models/v1").toURI());
        Path preambleModel = baseDir.resolve("preamble.smithy");
        List<String> lines = Files.readAllLines(preambleModel);
        DocumentPreamble preamble = Document.detectPreamble(lines);

        assertEquals("ns.preamble", preamble.getCurrentNamespace().get());
        assertEquals(new Position(2, 0), preamble.getNamespaceRange().getStart());
        assertEquals(new Position(2, 21), preamble.getNamespaceRange().getEnd());
        assertEquals("1.0", preamble.getIdlVersion().get());
        assertEquals(Optional.empty(), preamble.getOperationInputSuffix());
        assertEquals(Optional.empty(), preamble.getOperationOutputSuffix());
        assertEquals(new Position(4, 0), preamble.getUseBlockRange().getStart());
        assertEquals(new Position(6, 19), preamble.getUseBlockRange().getEnd());
        assertTrue(preamble.hasImport("ns.foo#FooTrait"));
        assertTrue(preamble.hasImport("ns.bar#BarTrait"));
        assertFalse(preamble.hasImport("ns.baz#Baz"));
        assertTrue(preamble.isBlankSeparated());
    }

    @Test
    public void detectPreambleV2() throws Exception {
        Path baseDir = Paths.get(Document.class.getResource("models/v2").toURI());
        Path preambleModel = baseDir.resolve("preamble.smithy");
        List<String> lines = Files.readAllLines(preambleModel);
        DocumentPreamble preamble = Document.detectPreamble(lines);

        assertEquals("ns.preamble", preamble.getCurrentNamespace().get());
        assertEquals(new Position(4, 0), preamble.getNamespaceRange().getStart());
        assertEquals(new Position(4, 21), preamble.getNamespaceRange().getEnd());
        assertEquals("2.0", preamble.getIdlVersion().get());
        assertEquals("Request", preamble.getOperationInputSuffix().get());
        assertEquals("Response", preamble.getOperationOutputSuffix().get());
        assertEquals(new Position(6, 0), preamble.getUseBlockRange().getStart());
        assertEquals(new Position(8, 19), preamble.getUseBlockRange().getEnd());
        assertTrue(preamble.hasImport("ns.foo#FooTrait"));
        assertTrue(preamble.hasImport("ns.bar#BarTrait"));
        assertFalse(preamble.hasImport("ns.baz#Baz"));
        assertTrue(preamble.isBlankSeparated());
    }

    @Test
    public void detectPreambleNonBlankSeparated() {
        List<String> lines = ListUtils.of(
                "$version: \"1.0\"",
                "namespace ns.preamble",
                "use ns.foo#Foo",
                "@Foo",
                "string MyString"
        );
        DocumentPreamble preamble = Document.detectPreamble(lines);

        assertEquals("ns.preamble", preamble.getCurrentNamespace().get());
        assertEquals(new Position(2, 0), preamble.getUseBlockRange().getStart());
        assertEquals(new Position(2, 14), preamble.getUseBlockRange().getEnd());
        assertFalse(preamble.isBlankSeparated());
    }

    @Test
    public void detectPreambleWithoutUseStatements() {
        List<String> lines = ListUtils.of(
                "$version: \"1.0\"",
                "namespace ns.preamble",
                "string MyString"
        );
        DocumentPreamble preamble = Document.detectPreamble(lines);

        assertEquals("ns.preamble", preamble.getCurrentNamespace().get());
        assertFalse(preamble.isBlankSeparated());
    }
}
