package software.amazon.smithy.lsp;

import static org.junit.Assert.assertNull;
import static software.amazon.smithy.lsp.TestUtils.MAIN_MODEL_FILENAME;
import static software.amazon.smithy.lsp.TestUtils.getV1Dir;
import static software.amazon.smithy.lsp.TestUtils.getV2Dir;
import static software.amazon.smithy.lsp.TestUtils.assertStringContains;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.Test;
import software.amazon.smithy.cli.dependencies.DependencyResolver;
import software.amazon.smithy.cli.dependencies.ResolvedArtifact;
import software.amazon.smithy.lsp.ext.Harness;
import software.amazon.smithy.lsp.ext.MockDependencyResolver;

public class HoverProviderTest {

    @Test
    public void shapeNameInCommentHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 43, 37).getValue();
        assertStringContains(v1, "structure MultiTrait");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 45, 37).getValue();
        assertStringContains(v2, "structure MultiTrait");
    }

    @Test
    public void preludeTraitHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 25, 3).getValue();
        assertStringContains(v1, "/// Specializes a structure for use only as the input");
        assertStringContains(v1, "structure input {}");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 27, 3).getValue();
        assertStringContains(v2, "/// Specializes a structure for use only as the input");
        assertStringContains(v2, "structure input {}");
    }

    @Test
    public void preludeMemberTraitHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 59, 10).getValue();
        assertStringContains(v1, "/// Marks a structure member as required");
        assertStringContains(v1, "structure required {}");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 61, 10).getValue();
        assertStringContains(v2, "/// Marks a structure member as required");
        assertStringContains(v2, "structure required {}");
    }

    @Test
    public void preludeTargetHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 36, 12).getValue();
        assertStringContains(v1, "string String");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 38, 12).getValue();
        assertStringContains(v2, "string String");
    }

    @Test
    public void memberTargetHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 12, 18).getValue();
        assertStringContains(v1, "structure SingleLine {}");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 14, 18).getValue();
        assertStringContains(v2, "structure SingleLine {}");
    }

    @Test
    public void memberIdentifierHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 65, 7).getValue();
        assertStringContains(v1, "string String");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 67, 7).getValue();
        assertStringContains(v2, "string String");
    }

    @Test
    public void selfHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 36, 0).getValue();
        assertStringContains(v1, "@input\n@tags");
        assertStringContains(v1, "structure MultiTraitAndLineComments {\n    a: String\n}");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 38, 0).getValue();
        assertStringContains(v2, "@input\n@tags");
        assertStringContains(v2, "structure MultiTraitAndLineComments {\n    a: String\n}");
    }

    @Test
    public void operationInputHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 52, 16).getValue();
        assertStringContains(v1, "structure MyOperationInput");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 54, 16).getValue();
        assertStringContains(v2, "structure MyOperationInput");
    }

    @Test
    public void operationOutputHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 53, 17).getValue();
        assertStringContains(v1, "structure MyOperationOutput");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 55, 17).getValue();
        assertStringContains(v2, "structure MyOperationOutput");
    }

    @Test
    public void operationErrorHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 54, 14).getValue();
        assertStringContains(v1, "structure MyError");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 56, 14).getValue();
        assertStringContains(v2, "structure MyError");
    }

    @Test
    public void resourceIdHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 75, 28).getValue();
        assertStringContains(v1, "string MyId");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 77, 28).getValue();
        assertStringContains(v2, "string MyId");
    }

    @Test
    public void resourceReadHover() {
        String v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 76, 12).getValue();
        assertStringContains(v1, "operation MyOperation");

        String v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 78, 12).getValue();
        assertStringContains(v2, "operation MyOperation");
    }

    @Test
    public void noMatchHover() {
        MarkupContent v1 = getHoverContent(getV1Dir(), MAIN_MODEL_FILENAME, 0, 0);
        assertNull(v1.getValue());

        MarkupContent v2 = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 0, 0);
        assertNull(v2.getValue());
    }

    @Test
    public void multiFileHover() {
        String filename = "test.smithy";
        String importsFilename = "extras-to-import.smithy";
        String v1 = getHoverContent(getV1Dir(), filename, 6, 15, MAIN_MODEL_FILENAME, importsFilename).getValue();
        assertStringContains(v1, "structure emptyTraitStruct");

        String v2 = getHoverContent(getV2Dir(), filename, 7, 15, MAIN_MODEL_FILENAME, importsFilename).getValue();
        assertStringContains(v2, "structure emptyTraitStruct");
    }

    @Test
    public void operationInlineMixinHover() {
        String content = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 143, 36).getValue();
        assertStringContains(content, "@mixin\nstructure UserDetails");
    }

    @Test
    public void falseOperationInlineHover() {
        String content = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 176, 18).getValue();
        assertStringContains(content, "structure FalseInlinedFooInput");
    }

    @Test
    public void providesHoverContentWhenThereAreUnknownTraits() {
        String contents = getHoverContent(getBaseDir(), "unknown-trait.smithy", 7, 10).getValue();
        assertStringContains(contents, "structure Foo");
    }

    @Test
    public void hoverContentIncludesValidations() {
        String contents = getHoverContent(getBaseDir(), "unknown-trait.smithy", 7, 10).getValue();
        assertStringContains(contents, "WARNING: Unable to resolve trait `com.external#unknownTrait`");
    }

    @Test
    public void hoverContentIncludesNamespace() {
        String contents = getHoverContent(getV2Dir(), MAIN_MODEL_FILENAME, 21, 4).getValue();
        assertStringContains(contents, "namespace smithy.api");
    }

    @Test
    public void hoverContentIncludesImports() {
        String contents = getHoverContent(getV2Dir(), "cluttered-preamble.smithy", 26, 11, "extras-to-import.smithy", "test.smithy").getValue();
        assertStringContains(contents, "use com.example#OtherStructure");
        assertStringContains(contents, "use com.extras#Extra");
    }

    @Test
    public void traitsFromJars() {
        String modelFilename = "test-traits.smithy";
        String jarFilename = "smithy-test-traits.jar";
        Path rootDir = getBaseDir().resolve("jars");
        Path jarImportModelPath = rootDir.resolve(jarFilename);

        DependencyResolver dependencyResolver = new MockDependencyResolver(
                ResolvedArtifact.fromCoordinates(jarImportModelPath, "com.example:smithy-test-traits:0.0.1")
        );
        Harness.Builder builder = Harness.builder()
                .dependencyResolver(dependencyResolver);

        String contents = getHoverContent(builder, rootDir, modelFilename, 6, 2).getValue();
        assertStringContains(contents, "structure test");
    }

    private static Path getBaseDir() {
        try {
            return Paths.get(HoverProviderTest.class.getResource("ext/models").toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MarkupContent getHoverContent(Path rootDir, String filename, int line, int column, String... otherModels) {
        return getHoverContent(Harness.builder(), rootDir, filename, line, column, otherModels);
    }

    private static MarkupContent getHoverContent(
            Harness.Builder builder,
            Path rootDir,
            String filename,
            int line,
            int column,
            String... otherModels
    ) {
        List<Path> paths = new ArrayList<>();
        for (String model: otherModels) {
            paths.add(rootDir.resolve(model));
        }
        paths.add(rootDir.resolve(filename));
        try (Harness hs = builder.paths(paths).build()) {
            StubClient client = new StubClient();
            SmithyTextDocumentService tds = new SmithyTextDocumentService(Optional.of(client), hs.getTempFolder());
            tds.setProject(hs.getProject());
            TextDocumentIdentifier tdi = new TextDocumentIdentifier(hs.file(filename).toString());

            HoverParams params = new HoverParams(tdi, new Position(line, column));
            try {
                return tds.hover(params).get().getContents().getRight();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
