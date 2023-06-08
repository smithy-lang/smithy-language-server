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

import java.nio.file.Path;
import software.amazon.smithy.build.model.SmithyBuildConfig;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;
import software.amazon.smithy.model.loader.ModelSyntaxException;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeMapper;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.utils.IoUtils;

public final class SmithyBuildLoader {
    private SmithyBuildLoader() {
    }

    /**
     * Loads Smithy build definition from a json file.
     *
     * @param path json file with build definition
     * @return loaded build definition
     * @throws ValidationException if any errors are encountered
     */
    public static SmithyBuildExtensions load(Path path) throws ValidationException {
        try {
            String content = IoUtils.readUtf8File(path);
            return loadAndMerge(path, content);
        } catch (ModelSyntaxException e) {
            throw new ValidationException(e.toString());
        }
    }

    static SmithyBuildExtensions load(Path path, String content) throws ValidationException {
        try {
            return loadAndMerge(path, content);
        } catch (ModelSyntaxException e) {
            throw new ValidationException(e.toString());
        }
    }

    private static SmithyBuildExtensions loadAndMerge(Path path, String content) {
        SmithyBuildExtensions config = loadExtension(loadWithJson(path, content).expectObjectNode());
        config.mergeMavenFromSmithyBuildConfig(SmithyBuildConfig.load(path));
        return config;
    }

    private static Node loadWithJson(Path path, String contents) {
        return Node.parseJsonWithComments(contents, path.toString());
    }

    private static SmithyBuildExtensions loadExtension(ObjectNode node) {
        NodeMapper mapper = new NodeMapper();
        return mapper.deserialize(node, SmithyBuildExtensions.class);
    }

}
