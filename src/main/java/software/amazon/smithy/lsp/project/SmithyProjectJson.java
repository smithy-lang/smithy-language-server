/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import java.util.List;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;

record SmithyProjectJson(
        List<String> sources,
        List<String> imports,
        List<ProjectDependency> dependencies,
        String outputDirectory,
        ObjectNode diff
) {
    static SmithyProjectJson empty() {
        return new SmithyProjectJson(List.of(), List.of(), List.of(), null, null);
    }

    static SmithyProjectJson fromNode(Node node) {
        ObjectNode objectNode = node.expectObjectNode();

        List<String> sources = objectNode.getArrayMember("sources")
                .map(arrayNode -> arrayNode.getElementsAs(StringNode.class).stream()
                        .map(StringNode::getValue)
                        .toList())
                .orElse(List.of());

        List<String> imports = objectNode.getArrayMember("imports")
                .map(arrayNode -> arrayNode.getElementsAs(StringNode.class).stream()
                        .map(StringNode::getValue)
                        .toList())
                .orElse(List.of());

        List<ProjectDependency> dependencies = objectNode.getArrayMember("dependencies")
                .map(arrayNode -> arrayNode.getElements().stream()
                        .map(ProjectDependency::fromNode)
                        .toList())
                .orElse(List.of());

        String outputDirectory = objectNode.getStringMemberOrDefault("outputDirectory", null);

        // Kept as the raw node: DiffConfig.fromNode throws SourceException on a malformed block,
        // and throwing from here would make the loader discard this ENTIRE file's config
        // (sources, imports, dependencies) over a typo in the optional diff block. The loader
        // parses it separately so its errors surface as a diagnostic without that blast radius.
        ObjectNode diff = objectNode.getObjectMember("diff").orElse(null);

        return new SmithyProjectJson(sources, imports, dependencies, outputDirectory, diff);
    }

    /**
     * An arbitrary project dependency, used to specify non-maven projectDependencies
     * that exist locally.
     *
     * @param name The name of the dependency
     * @param path The path of the dependency
     */
    record ProjectDependency(String name, String path) {
        static ProjectDependency fromNode(Node node) {
            ObjectNode objectNode = node.expectObjectNode();
            String name = objectNode.expectStringMember("name").getValue();
            String path = objectNode.expectStringMember("path").getValue();
            return new ProjectDependency(name, path);
        }
    }
}
