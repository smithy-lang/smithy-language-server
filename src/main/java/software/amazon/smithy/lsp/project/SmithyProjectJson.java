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
        String outputDirectory
) {
    static SmithyProjectJson empty() {
        return new SmithyProjectJson(List.of(), List.of(), List.of(), null);
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

        return new SmithyProjectJson(sources, imports, dependencies, outputDirectory);
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
