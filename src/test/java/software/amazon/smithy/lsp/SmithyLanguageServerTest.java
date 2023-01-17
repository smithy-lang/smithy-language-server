package software.amazon.smithy.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;

import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import software.amazon.smithy.lsp.codeactions.SmithyCodeActions;
import software.amazon.smithy.utils.ListUtils;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SmithyLanguageServerTest {
    @Test
    public void initializeServer() throws Exception {
        InitializeParams initParams = new InitializeParams();
        File temp = Files.createTempDirectory("smithy-lsp-test").toFile();
        temp.deleteOnExit();
        initParams.setWorkspaceFolders(ListUtils.of(new WorkspaceFolder(temp.toURI().toString())));
        SmithyLanguageServer languageServer = new SmithyLanguageServer();
        InitializeResult initResults = languageServer.initialize(initParams).get();
        ServerCapabilities capabilities = initResults.getCapabilities();

        assertNull(languageServer.tempWorkspaceRoot);
        assertEquals(TextDocumentSyncKind.Full, capabilities.getTextDocumentSync().getLeft());
        assertEquals(new CodeActionOptions(SmithyCodeActions.all()), capabilities.getCodeActionProvider().getRight());
        assertTrue(capabilities.getDefinitionProvider().getLeft());
        assertTrue(capabilities.getDeclarationProvider().getLeft());
        assertEquals(new CompletionOptions(true, null), capabilities.getCompletionProvider());
        assertTrue(capabilities.getHoverProvider().getLeft());
    }

    @Test
    public void initializeWithTemporaryWorkspace() {
        InitializeParams initParams = new InitializeParams();
        SmithyLanguageServer languageServer = new SmithyLanguageServer();
        languageServer.initialize(initParams);

        assertNotNull(languageServer.tempWorkspaceRoot);
        assertTrue(languageServer.tempWorkspaceRoot.exists());
        assertTrue(languageServer.tempWorkspaceRoot.isDirectory());
        assertTrue(languageServer.tempWorkspaceRoot.canWrite());
    }
}
