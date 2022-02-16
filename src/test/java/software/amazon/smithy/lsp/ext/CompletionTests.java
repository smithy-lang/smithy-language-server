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

import static org.junit.Assert.assertEquals;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.Test;

import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

public class CompletionTests {

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
