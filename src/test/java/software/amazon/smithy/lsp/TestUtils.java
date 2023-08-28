package software.amazon.smithy.lsp;

import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import junit.framework.AssertionFailedError;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.ext.SmithyProjectTest;

public final class TestUtils {
    public static final String MAIN_MODEL_FILENAME = "main.smithy";

    public static Path getV1Dir() {
        try {
            return Paths.get(SmithyProjectTest.class.getResource("models/v1").toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Path getV2Dir() {
        try {
            return Paths.get(SmithyProjectTest.class.getResource("models/v2").toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void assertLocationEquals(
            Location actual,
            String filename,
            int startLine,
            int startCol,
            int endLine,
            int endCol
    ) {
        Range actualRange = actual.getRange();
        Range expectedRange = new Range(new Position(startLine, startCol), new Position(endLine, endCol));
        if (!expectedRange.equals(actualRange)) {
            throw new AssertionFailedError("Expected range:\n" + expectedRange + "\nBut was:\n" + actualRange);
        }
        if (!actual.getUri().endsWith(filename)) {
            throw new AssertionFailedError("Expected uri to end with filename:\n" + filename +
                    "\nBut was:\n" + actual.getUri());
        }
    }

    public static void assertStringContains(String actual, String expected) {
        if (!actual.contains(expected)) {
            throw new AssertionError("Expected string to contain:\n----------\n" + expected + "\n----------\n\n"
                    + "but got:\n----------\n" + actual + "\n----------\n");
        }
        assertTrue(actual.contains(expected));
    }
}
