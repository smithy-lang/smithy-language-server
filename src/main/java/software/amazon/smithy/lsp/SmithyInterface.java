package software.amazon.smithy.lsp;

import java.io.File;

import org.eclipse.lsp4j.jsonrpc.messages.Either;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidatedResult;

public class SmithyInterface {
  public static Either<Exception, ValidatedResult<Model>> readModel(File path) {

    try {
      return Either.forRight(Model.assembler().discoverModels().addImport(path.getAbsolutePath()).assemble());
    } catch (Exception e) {
      return Either.forLeft(e);
    }
  }
}
