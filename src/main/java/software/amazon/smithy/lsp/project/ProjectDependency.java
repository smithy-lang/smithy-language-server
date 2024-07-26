/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;

/**
 * An arbitrary project dependency, used to specify non-maven dependencies
 * that exist locally.
 */
final class ProjectDependency {
    private final String name;
    private final String path;

    private ProjectDependency(String name, String path) {
        this.name = name;
        this.path = path;
    }

    static ProjectDependency fromNode(Node node) {
        ObjectNode objectNode = node.expectObjectNode();
        String name = objectNode.expectStringMember("name").getValue();
        String path = objectNode.expectStringMember("path").getValue();
        return new ProjectDependency(name, path);
    }

    /**
     * @return The name of the dependency
     */
    public String name() {
        return name;
    }

    /**
     * @return The path of the dependency
     */
    public String path() {
        return path;
    }
}
