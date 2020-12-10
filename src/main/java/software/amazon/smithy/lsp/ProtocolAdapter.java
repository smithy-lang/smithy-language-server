package software.amazon.smithy.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ProtocolAdapter {
  public static Diagnostic toDiagnostic(ValidationEvent ev) {
    int line = ev.getSourceLocation().getLine() - 1;
    int col = ev.getSourceLocation().getColumn() - 1;

    DiagnosticSeverity sev = toDiagSeverity(ev.getSeverity());

    Range range = new Range(new Position(line, col), new Position(line, col));

    return new Diagnostic(range, ev.getMessage(), sev, "Smithy LSP");
  }

  public static DiagnosticSeverity toDiagSeverity(Severity sev) {
    if (sev == Severity.DANGER)
      return DiagnosticSeverity.Error;
    else if (sev == Severity.ERROR)
      return DiagnosticSeverity.Error;
    else if (sev == Severity.WARNING)
      return DiagnosticSeverity.Warning;
    else if (sev == Severity.NOTE)
      return DiagnosticSeverity.Information;
    else
      return DiagnosticSeverity.Hint;
  }

}
