package software.amazon.smithy.lsp.ext;

import org.eclipse.lsp4j.Location;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.SmithyUnstableApi;

import java.util.Map;

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
