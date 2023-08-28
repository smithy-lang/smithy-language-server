package software.amazon.smithy.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static software.amazon.smithy.lsp.TestUtils.MAIN_MODEL_FILENAME;
import static software.amazon.smithy.lsp.TestUtils.getV1Dir;
import static software.amazon.smithy.lsp.TestUtils.getV2Dir;
import static software.amazon.smithy.lsp.TestUtils.assertLocationEquals;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Test;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import software.amazon.smithy.lsp.ext.Harness;

public class DefinitionProviderTest {

    @Test
    public void shapeNameInCommentDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 43, 37).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 20, 0, 21, 14);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 45, 37).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 22, 0, 23, 14);
    }

    @Test
    public void preludeTargetDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 36, 12).get(0);
        assertTrue(v1.getUri().endsWith("prelude.smithy"));

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 38, 12).get(0);
        assertTrue(v2.getUri().endsWith("prelude.smithy"));
    }

    @Test
    public void preludeTraitDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 25, 3).get(0);
        assertTrue(v1.getUri().endsWith("prelude.smithy"));

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 27, 3).get(0);
        assertTrue(v2.getUri().endsWith("prelude.smithy"));
    }

    @Test
    public void preludeMemberTraitDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 59, 10).get(0);
        assertTrue(v1.getUri().endsWith("prelude.smithy"));

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 61, 10).get(0);
        assertTrue(v2.getUri().endsWith("prelude.smithy"));
    }

    @Test
    public void memberTargetDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 12, 18).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 4, 0, 4, 23);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 14, 18).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 6, 0, 6, 23);
    }

    @Test
    public void selfDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 35, 0).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 35, 0, 37, 1);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 37, 0).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 37, 0, 39, 1);
    }

    @Test
    public void operationInputDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 52, 16).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 57, 0, 61, 1);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 54, 16).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 59, 0, 63, 1);
    }

    @Test
    public void operationOutputDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 53, 17).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 63, 0, 66, 1);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 55, 17).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 65, 0, 68, 1);
    }

    @Test
    public void operationErrorDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 54, 14).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 69, 0, 72, 1);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 56, 14).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 71, 0, 74, 1);
    }

    @Test
    public void resourceReadDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 76, 12).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 51, 0, 55, 1);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 78, 12).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 53, 0, 57, 1);
    }

    @Test
    public void noMatchDefinition() {
        List<? extends Location> v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 0, 0);
        assertTrue(v1.isEmpty());

        List<? extends Location> v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 0, 0);
        assertTrue(v2.isEmpty());
    }

    @Test
    public void resourceIdDefinition() {
        Location v1 = getDefinitionLocations(getV1Dir(), MAIN_MODEL_FILENAME, 75, 28).get(0);
        assertLocationEquals(v1, MAIN_MODEL_FILENAME, 79, 0, 79, 11);

        Location v2 = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 77, 28).get(0);
        assertLocationEquals(v2, MAIN_MODEL_FILENAME, 81, 0, 81, 11);
    }

    @Test
    public void operationInputMixinDefinition() {
        Location location = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 143, 24).get(0);
        assertLocationEquals(location, MAIN_MODEL_FILENAME, 112, 0, 118, 1);
    }

    @Test
    public void operationOutputMixinDefinition() {
        Location location = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 149, 36).get(0);
        assertLocationEquals(location, MAIN_MODEL_FILENAME, 121, 0, 123, 1);
    }

    @Test
    public void structureMixinDefinition() {
        Location location = getDefinitionLocations(getV2Dir(), MAIN_MODEL_FILENAME, 134, 36).get(0);
        assertLocationEquals(location, MAIN_MODEL_FILENAME, 112, 0, 118, 1);
    }

    @Test
    public void whenThereAreUnknownTraits() throws Exception {
        Path rootDir = Paths.get(getClass().getResource("ext/models").toURI());
        List<? extends Location> locations = getDefinitionLocations(rootDir, "unknown-trait.smithy", 10, 13);
        assertEquals(locations.size(), 1);
    }

    private static List<? extends Location> getDefinitionLocations(Path rootDir, String filename, int line, int column) {
        Path model = rootDir.resolve(filename);
        try (Harness hs = Harness.builder().paths(model).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());
            TextDocumentIdentifier tdi = new TextDocumentIdentifier(hs.file(filename).toString());
            DefinitionParams params = getDefinitionParams(tdi, line, column);
            try {
                return tds.definition(params).get().getLeft();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static DefinitionParams getDefinitionParams(TextDocumentIdentifier tdi, int line, int character) {
        return new DefinitionParams(tdi, new Position(line, character));
    }
}
