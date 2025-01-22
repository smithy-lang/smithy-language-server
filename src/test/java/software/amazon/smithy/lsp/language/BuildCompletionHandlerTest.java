/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.language;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static software.amazon.smithy.lsp.LspMatchers.hasLabelAndEditText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.lsp.RequestBuilders;
import software.amazon.smithy.lsp.TextWithPositions;
import software.amazon.smithy.lsp.project.BuildFile;
import software.amazon.smithy.lsp.project.BuildFileType;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectTest;
import software.amazon.smithy.lsp.protocol.LspAdapter;

public class BuildCompletionHandlerTest {
    @Test
    public void completesSmithyBuildJsonTopLevel() {
        var text = TextWithPositions.from("""
                {
                    %
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("version", """
                        "version": "1"
                        """),
                hasLabelAndEditText("outputDirectory", """
                        "outputDirectory": ""
                        """),
                hasLabelAndEditText("sources", """
                        "sources": []
                        """),
                hasLabelAndEditText("imports", """
                        "imports": []
                        """),
                hasLabelAndEditText("projections", """
                        "projections": {}
                        """),
                hasLabelAndEditText("plugins", """
                        "plugins": {}
                        """),
                hasLabelAndEditText("ignoreMissingPlugins", """
                        "ignoreMissingPlugins":
                        """),
                hasLabelAndEditText("maven", """
                        "maven": {}
                        """)
                ));
    }

    @Test
    public void completesSmithyBuildJsonProjectionMembers() {
        var text = TextWithPositions.from("""
                {
                    "projections": {
                        "foo": {
                            %
                        }
                    }
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("abstract", """
                        "abstract":
                        """),
                hasLabelAndEditText("imports", """
                        "imports": []
                        """),
                hasLabelAndEditText("transforms", """
                        "transforms": []
                        """),
                hasLabelAndEditText("plugins", """
                        "plugins": {}
                        """)
        ));
    }

    @Test
    public void completesSmithyBuildJsonTransformMembers() {
        var text = TextWithPositions.from("""
                {
                    "projections": {
                        "foo": {
                            "transforms": [
                                {
                                    %
                                }
                            ]
                        }
                    }
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("name", """
                        "name": ""
                        """),
                hasLabelAndEditText("args", """
                        "args": {}
                        """)
        ));
    }

    @Test
    public void completesSmithyBuildJsonMavenMembers() {
        var text = TextWithPositions.from("""
                {
                    "maven": {
                        %
                    }
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("dependencies", """
                        "dependencies": []
                        """),
                hasLabelAndEditText("repositories", """
                        "repositories": []
                        """)
        ));
    }

    @Test
    public void completesSmithyBuildJsonMavenRepoMembers() {
        var text = TextWithPositions.from("""
                {
                    "maven": {
                        "repositories": [
                            {
                                %
                            }
                        ]
                    }
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("url", """
                        "url": ""
                        """),
                hasLabelAndEditText("httpCredentials", """
                        "httpCredentials": ""
                        """),
                hasLabelAndEditText("proxyHost", """
                        "proxyHost": ""
                        """),
                hasLabelAndEditText("proxyCredentials", """
                        "proxyCredentials": ""
                        """)
        ));
    }

    @Test
    public void completesSmithyProjectJsonTopLevel() {
        var text = TextWithPositions.from("""
                {
                    %
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_PROJECT);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("sources", """
                        "sources": []
                        """),
                hasLabelAndEditText("imports", """
                        "imports": []
                        """),
                hasLabelAndEditText("outputDirectory", """
                        "outputDirectory": ""
                        """),
                hasLabelAndEditText("dependencies", """
                        "dependencies": []
                        """)
        ));
    }

    @Test
    public void completesSmithyProjectJsonDependencyMembers() {
        var text = TextWithPositions.from("""
                {
                    "dependencies": [
                        {
                            %
                        }
                    ]
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_PROJECT);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("name", """
                        "name": ""
                        """),
                hasLabelAndEditText("path", """
                        "path": ""
                        """)
        ));
    }

    @Test
    public void matchesStringKeys() {
        var text = TextWithPositions.from("""
                {
                    "v%"
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("version", """
                        "version": "1"
                        """)
        ));
    }

    @Test
    public void matchesNonStringKeys() {
        var text = TextWithPositions.from("""
                {
                    v%
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("version", """
                        "version": "1"
                        """)
        ));
    }

    @Test
    public void completesKeyValues() {
        var text = TextWithPositions.from("""
                {
                    "version": %,
                    "projections": {
                        "a": {
                            "abstract": %
                        },
                        "b": {
                            "imports": %
                        },
                        "c": {
                            "plugins": %
                        }
                    }
                }
                """);
        var items = getCompItems(text, BuildFileType.SMITHY_BUILD);

        assertThat(items, containsInAnyOrder(
                hasLabelAndEditText("\"1\"", """
                        "1"
                        """),
                hasLabelAndEditText("false", "false"),
                hasLabelAndEditText("true", "true"),
                hasLabelAndEditText("[]", "[]"),
                hasLabelAndEditText("{}", "{}")
        ));
    }

    private static List<CompletionItem> getCompItems(TextWithPositions twp, BuildFileType type) {
        try {
            Path root = Files.createTempDirectory("test");
            Path path = root.resolve(type.filename());
            Files.writeString(path, twp.text());
            Project project = ProjectTest.load(root);
            String uri = LspAdapter.toUri(path.toString());
            BuildFile buildFile = (BuildFile) project.getProjectFile(uri);
            List<CompletionItem> completionItems = new ArrayList<>();
            BuildCompletionHandler handler = new BuildCompletionHandler(project, buildFile);
            for (Position position : twp.positions()) {
                CompletionParams params = RequestBuilders.positionRequest()
                        .uri(uri)
                        .position(position)
                        .buildCompletion();
                completionItems.addAll(handler.handle(params));
            }
            return completionItems;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
