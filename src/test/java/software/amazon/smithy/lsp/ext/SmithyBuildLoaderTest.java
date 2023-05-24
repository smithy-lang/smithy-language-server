package software.amazon.smithy.lsp.ext;

import static junit.framework.TestCase.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import software.amazon.smithy.build.model.MavenRepository;
import software.amazon.smithy.lsp.ext.model.SmithyBuildExtensions;

public class SmithyBuildLoaderTest {

    @Test
    public void mergesSmithyBuildConfigWhenLoading() throws ValidationException {
        System.setProperty("FOO", "bar");
        SmithyBuildExtensions config = SmithyBuildLoader.load(getResourcePath());

        MavenRepository repository = config.getMavenConfig().getRepositories().stream().findFirst().get();
        assertTrue(repository.getUrl().contains("example.com/maven/my_repo"));
        assertTrue(repository.getHttpCredentials().get().contains("my_user:bar"));
    }

    private Path getResourcePath() {
        try {
            return Paths.get(SmithyBuildLoaderTest.class.getResource("config-with-env.json").toURI());
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
