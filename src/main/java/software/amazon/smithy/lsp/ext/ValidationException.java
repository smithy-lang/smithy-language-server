package software.amazon.smithy.lsp.ext;

public class ValidationException extends Exception {
  public ValidationException(String msg) {
    super(msg);
  }
}
