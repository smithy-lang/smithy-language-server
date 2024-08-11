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
