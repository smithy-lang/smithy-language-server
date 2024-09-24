/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.LspMatchers;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.ServerState;
import software.amazon.smithy.lsp.TestWorkspace;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.SmithyFile;

public class CompletionHandlerTest {
    @Test
    public void getsCompletions() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: String
                    baz: Integer
                }
                
                @foo(ba%)""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("bar", "baz"));
    }

    @Test
    public void completesTraitMemberValues() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: String
                    baz: Integer
                }
                
                @foo(bar: %)
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("\"\""));
    }

    @Test
    public void completesMetadataMemberValues() {
        TextWithPositions text = TextWithPositions.from("""
                metadata suppressions = [{
                    namespace:%
                }]""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, not(empty()));
    }

    @Test
    public void doesntDuplicateTraitBodyMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: String
                    baz: String
                }
                
                @foo(bar: "", ba%)""");
        List<String> comps = getCompLabels(text);

         assertThat(comps, containsInAnyOrder("baz"));
    }

    @Test
    public void doesntDuplicateMetadataMembers() {
        TextWithPositions text = TextWithPositions.from("""
                metadata suppressions = [{
                    namespace: "foo"
                %}]
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("id", "reason"));
    }

    @Test
    public void doesntDuplicateListAndMapMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                list L {
                    member: String
                %}
                map M {
                    key: String
                %}
                """);
        List<String> comps = getCompLabels(text);


        assertThat(comps, contains("value"));
    }

    @Test
    public void doesntDuplicateOperationMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                operation O {
                    input := {}
                %}
                """);
        List<String> comps = getCompLabels(text);
        assertThat(comps, containsInAnyOrder("output", "errors"));
    }

    @Test
    public void doesntDuplicateServiceMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                service S {
                    version: "2024-08-31"
                    operations: []
                %}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("rename", "resources", "errors"));
    }

    @Test
    public void doesntDuplicateResourceMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                resource R {
                    identifiers: {}
                    properties: {}
                    read: Op
                    create: Op
                %}
                
                operation Op {}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder(
                "list", "put", "delete", "update", "collectionOperations", "operations", "resources"));
    }

    @Test
    public void completesEnumTraitValues() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                enum foo {
                    ONE
                    TWO
                    THREE
                }
                
                @foo(T%)
                """);
        List<CompletionItem> comps = getCompItems(text.text(), text.positions());

        List<String> labels = comps.stream().map(CompletionItem::getLabel).toList();
        List<String> editText = comps.stream()
                .map(completionItem -> {
                    if (completionItem.getTextEdit() != null) {
                        return completionItem.getTextEdit().getLeft().getNewText();
                    } else {
                        return completionItem.getInsertText();
                    }
                }).toList();

        assertThat(labels, containsInAnyOrder("TWO", "THREE"));
        assertThat(editText, containsInAnyOrder("\"TWO\"", "\"THREE\""));
        // TODO: Fix this issue where the string is inserted within the enclosing ""
    }

    @Test
    public void completesFromSingleCharacter() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @http(m%""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("method"));
    }

    @Test
    public void completesBuiltinControlKeys() {
        TextWithPositions text = TextWithPositions.from("""
                $ver%
                $ope%""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder(
                startsWith("$version: \"2.0\""),
                startsWith("$operationInputSuffix: \"Input\""),
                startsWith("$operationOutputSuffix: \"Output\"")));
    }

    @Test
    public void completesBuiltinMetadataKeys() {
        TextWithPositions text = TextWithPositions.from("""
                metadata su%
                metadata va%""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("suppressions = []", "validators = []"));
    }

    @Test
    public void completesStatementKeywords() {
        TextWithPositions text = TextWithPositions.from("""
                us%
                ma%
                met%
                nam%
                blo%
                boo%
                str%
                byt%
                sho%
                int%
                lon%
                flo%
                dou%
                big%
                tim%
                doc%
                enu%
                lis%
                uni%
                ser%
                res%
                ope%
                app%""");
        List<String> comps = getCompLabels(text);

        String[] keywords = Candidates.KEYWORD.literals().toArray(new String[0]);
        assertThat(comps, containsInAnyOrder(keywords));
    }

    @Test
    public void completesServiceMembers() {
        TextWithPositions text = TextWithPositions.from("""
                service One {
                    ver%
                    ope%
                    res%
                    err%
                    ren%
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("version", "operations", "resources", "errors", "rename"));
    }

    @Test
    public void completesResourceMembers() {
        TextWithPositions text = TextWithPositions.from("""
                resource A {
                    ide%
                    pro%
                    cre%
                    pu%
                    rea%
                    upd%
                    del%
                    lis%
                    ope%
                    coll%
                    res%
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder(
                "identifiers",
                "properties",
                "create",
                "put",
                "read",
                "update",
                "delete",
                "list",
                "operations",
                "collectionOperations",
                "resources"));
    }

    @Test
    public void completesOperationMembers() {
        TextWithPositions text = TextWithPositions.from("""
                operation Op {
                    inp%
                    out%
                    err%
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("input", "output", "errors"));
    }

    @Test
    public void completesListAndMapMembers() {
        TextWithPositions text = TextWithPositions.from("""
                map M {
                    k%
                    v%
                }
                list L {
                    m%
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("key", "value", "member"));
    }

    @Test
    public void completesMetadataValues() {
        TextWithPositions text = TextWithPositions.from("""
                metadata validators = [{ nam% }]
                metadata suppressions = [{ rea% }]
                metadata severityOverrides = [{ sev% }]
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("namespaces", "name", "reason", "severity"));
    }

    @Test
    public void completesMetadataValueWithoutStartingCharacter() {
        TextWithPositions text = TextWithPositions.from("""
                metadata suppressions = [{%""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("id", "namespace", "reason"));
    }

    @Test
    public void completesTraitValueWithoutStartingCharacter() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                @http(%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("method", "uri", "code"));
    }

    @Test
    public void completesShapeMemberNameWithoutStartingCharacter() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                list Foo {
                %
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("member"));
    }

    // TODO: These next two shouldn't need the space after ':'
    @Test
    public void completesMemberTargetsWithoutStartingCharacter() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo
                structure Foo {
                    bar: %
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, hasItems("String", "Integer", "Float"));
    }

    @Test
    public void completesOperationMemberTargetsWithoutStartingCharacters() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo
                structure Foo {}
                operation Bar {
                    input: %
                }""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, hasItem("Foo"));
    }

    @Test
    public void completesTraitsWithoutStartingCharacter() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo
                @%""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, hasItem("http"));
    }

    @Test
    public void completesOperationErrors() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo
                @error("client")
                structure MyError {}
                
                operation Foo {
                    errors: [%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("MyError"));
    }

    @Test
    public void completesServiceMemberValues() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo
                service Foo {
                    operations: [%]
                    resources:  [%]
                    errors:     [%]
                }
                operation MyOp {}
                resource MyResource {}
                @error("client")
                structure MyError {}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("MyOp", "MyResource", "MyError"));
    }

    @Test
    public void completesResourceMemberValues() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo
                resource Foo {
                    create: M%
                    operations: [O%]
                    resources: [%]
                }
                operation MyOp {}
                operation OtherOp {}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("MyOp", "OtherOp", "Foo"));
    }

    @Test
    public void insertionTextHasCorrectRange() {
        TextWithPositions text = TextWithPositions.from("metadata suppressions = [%]");

        var comps = getCompItems(text.text(), text.positions());
        var edits = comps.stream().map(item -> item.getTextEdit().getLeft()).toList();

        assertThat(edits, LspMatchers.togetherMakeEditedDocument(Document.of(text.text()), "metadata suppressions = [{}]"));
    }

    @Test
    public void completesNamespace() {
        TextWithPositions text = TextWithPositions.from("""
                namespace com.foo%""");
        List<String> comps = getCompLabels(text);

        assertThat(comps, hasItem("com.foo"));
    }

    // TODO: This shouldn't need the space after the ':'
    @Test
    public void completesInlineOpMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                operation Op {
                    input :=
                        @tags([])
                        {
                        foo: %
                        }
                }
                """);
        List<String> comps = getCompLabels(text);


        assertThat(comps, hasItem("String"));
    }

    @Test
    public void completesNamespacesInMetadata() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                metadata suppressions = [{
                    id: "foo"
                    namespace:%
                }]
                namespace com.foo
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, hasItem("*"));
    }

    @Test
    public void completesSeverityInMetadata() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                metadata severityOverrides = [{
                    id: "foo"
                    severity:%
                }]
                namespace com.foo
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("WARNING", "DANGER"));
    }

    @Test
    public void completesValidatorNamesInMetadata() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                metadata validators = [{
                    id: "foo"
                    name:%
                }]
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, hasItems("EmitEachSelector", "EmitNoneSelector"));
    }

    @Test
    public void completesValidatorConfigInMetadata() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                metadata validators = [{
                    id: "foo"
                    name: "EmitNoneSelector"
                    configuration: {%}
                }]
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("selector"));
    }

    @Test
    public void doesntCompleteTraitsAfterClosingParen() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @error("client")%
                structure Foo {}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, empty());
    }

    @Test
    public void doesntCompleteTraitsAfterClosingParen2() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bool: Boolean
                }
                
                @foo(bool: true)%
                structure Foo {}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, empty());
    }

    @Test
    public void recursiveTraitDef() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: Bar
                }
                
                structure Bar {
                    bar: Bar
                }
                
                @foo(bar: { bar: { b%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("bar"));
    }

    @Test
    public void recursiveTraitDef2() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: Bar
                }
                
                structure Bar {
                    one: Baz
                }
                
                structure Baz {
                    two: Bar
                }
                
                @foo(bar: { one: { two: { o%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("one"));
    }

    @Test
    public void recursiveTraitDef3() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: Bar
                }
                
                list Bar {
                    member: Baz
                }
                
                structure Baz {
                    bar: Bar
                }
                
                @foo(bar: [{bar: [{b%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("bar"));
    }

    @Test
    public void recursiveTraitDef4() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: Bar
                }
                
                structure Bar {
                    baz: Baz
                }
                
                list Baz {
                    member: Bar
                }
                
                @foo(bar: {baz:[{baz:[{b%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("baz"));
    }

    @Test
    public void recursiveTraitDef5() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    bar: Bar
                }
                
                structure Bar {
                    baz: Baz
                }
                
                map Baz {
                    key: String
                    value: Bar
                }
                
                @foo(bar: {baz: {key: {baz: {key: {b%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("baz"));
    }

    @Test
    public void completesInlineForResource() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                resource MyResource {
                }
                
                operation Foo {
                    input := for %
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("MyResource"));
    }

    @Test
    public void completesElidedMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                resource MyResource {
                    identifiers: { one: String }
                    properties: { abc: String }
                }
                
                resource MyResource2 {
                    identifiers: { two: String }
                    properties: { def: String }
                }
                
                @mixin
                structure MyMixin {
                    foo: String
                }
                
                @mixin
                structure MyMixin2 {
                    bar: String
                }
                
                structure One for MyResource {
                    $%
                }
                
                structure Two with [MyMixin] {
                    $%
                }
                
                operation MyOp {
                    input := for MyResource2 {
                        $%
                    }
                    output := with [MyMixin2] {
                        $%
                    }
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("$one", "$foo", "$two", "$bar", "$abc", "$def"));
    }

    @Test
    public void traitsWithMaps() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @trait
                structure foo {
                    myMap: MyMap
                }
                
                map MyMap {
                    key: String
                    value: String
                }
                
                @foo(myMap: %)
                structure A {}
                
                @foo(myMap: {%})
                structure B {}
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("{}"));
    }

    @Test
    public void applyTarget() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                string Zzz
                
                apply Z%
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("Zzz"));
    }

    @Test
    public void enumMapKeys() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                enum Keys {
                    FOO = "foo"
                    BAR = "bar"
                }
                
                @trait
                map mapTrait {
                    key: Keys
                    value: String
                }
                
                @mapTrait(%)
                string Foo
                
                @mapTrait({%})
                string Bar
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("FOO", "BAR", "FOO", "BAR"));
    }

    @Test
    public void dynamicTraitValues() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace smithy.test
                
                @trait
                list smokeTests {
                    member: SmokeTestCase
                }
                
                structure SmokeTestCase {
                    params: Document
                    vendorParams: Document
                    vendorParamsShape: ShapeId
                }
                
                @idRef
                string ShapeId
                
                @smokeTests([
                    {
                        params: {%}
                        vendorParamsShape: MyVendorParams
                        vendorParams: {%}
                    }
                ])
                operation Foo {
                    input := {
                        bar: String
                    }
                }
                
                structure MyVendorParams {
                    abc: String
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("bar", "abc"));
    }

    @Test
    public void doesntDuplicateElidedMembers() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @mixin
                structure Foo {
                    abc: String
                    ade: String
                }
                
                structure Bar with [Foo] {
                    $abc
                    $%
                }
                
                structure Baz with [Foo] {
                    abc: String
                    $%
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("$ade", "$ade"));
    }

    @Test
    public void knownMemberNamesWithElided() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @mixin
                map Foo {
                    key: String
                    value: String
                }
                
                map Bar with [Foo] {
                    key: String
                    %
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("value", "$value"));
    }

    @Test
    public void unknownMemberNamesWithElided() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @mixin
                structure Foo {
                    abc: String
                    def: String
                }
                
                structure Bar with [Foo] {
                    $abc
                    %
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("$def"));
    }

    @Test
    public void completesElidedMembersWithoutLeadingDollar() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                
                @mixin
                structure Foo {
                    abc: String
                }
                
                structure Bar with [Foo] {
                    ab%
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, contains("$abc"));
    }

    @Test
    public void completesNodeMemberTargetStart() {
        TextWithPositions text = TextWithPositions.from("""
                $version: "2"
                namespace com.foo
                service A {
                    version: %
                }
                service B {
                    operations: %
                }
                resource C {
                    identifiers: %
                }
                operation D {
                    errors: %
                }
                """);
        List<String> comps = getCompLabels(text);

        assertThat(comps, containsInAnyOrder("\"\"", "[]", "{}", "[]"));
    }

    private static List<String> getCompLabels(TextWithPositions textWithPositions) {
        return getCompLabels(textWithPositions.text(), textWithPositions.positions());
    }

    private static List<String> getCompLabels(String text, Position... positions) {
        return getCompItems(text, positions).stream().map(CompletionItem::getLabel).toList();
    }

    private static List<CompletionItem> getCompItems(String text, Position... positions) {
        TestWorkspace workspace = TestWorkspace.singleModel(text);
        Project project = ProjectLoader.load(workspace.getRoot(), new ServerState()).unwrap();
        String uri = workspace.getUri("main.smithy");
        SmithyFile smithyFile = project.getSmithyFile(uri);

        List<CompletionItem> completionItems = new ArrayList<>();
        CompletionHandler handler = new CompletionHandler(project, smithyFile);
        for (Position position : positions) {
            CompletionParams params = RequestBuilders.positionRequest()
                    .uri(uri)
                    .position(position)
                    .buildCompletion();
            completionItems.addAll(handler.handle(params, () -> {}));
        }

        return completionItems;
    }
}
