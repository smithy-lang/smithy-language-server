/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public final class ProtocolAdapter {
  private ProtocolAdapter() {

  }

  /**
   * @param event ValidationEvent to be converted to a Diagnostic.
   * @return Returns a Diagnostic from a ValidationEvent.
   */
  public static Diagnostic toDiagnostic(ValidationEvent event) {
    int line = event.getSourceLocation().getLine() - 1;
    int col = event.getSourceLocation().getColumn() - 1;

    DiagnosticSeverity severity = toDiagnosticSeverity(event.getSeverity());

    Range range = new Range(new Position(line, 0), new Position(line, col));

    final String message = event.getId() + ": " + event.getMessage();

    return new Diagnostic(range, message, severity, "Smithy LSP");
  }

  /**
   * @param severity Severity to be converted to a DiagnosticSeverity.
   * @return Returns a DiagnosticSeverity from a Severity.
   */
  public static DiagnosticSeverity toDiagnosticSeverity(Severity severity) {
    if (severity == Severity.DANGER) {
      return DiagnosticSeverity.Error;
    } else if (severity == Severity.ERROR) {
      return DiagnosticSeverity.Error;
    } else if (severity == Severity.WARNING) {
      return DiagnosticSeverity.Warning;
    } else if (severity == Severity.NOTE) {
      return DiagnosticSeverity.Information;
    } else {
      return DiagnosticSeverity.Hint;
    }
  }
}
