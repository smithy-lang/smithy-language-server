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

import java.util.Map;
import org.eclipse.lsp4j.Location;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.SmithyUnstableApi;

/**
 * Interface used to get the shape location information
 * used by the language server.
 */
@SmithyUnstableApi
interface ShapeLocationCollector {

    /**
     * Collects the definition locations of all shapes in the model.
     *
     * @param model Model to collect shape definition locations for
     * @return Map of {@link ShapeId} to its definition {@link Location}
     */
    Map<ShapeId, Location> collectDefinitionLocations(Model model);
}
