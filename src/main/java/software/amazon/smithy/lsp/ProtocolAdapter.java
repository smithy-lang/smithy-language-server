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

import java.util.Optional;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import software.amazon.smithy.model.shapes.ShapeType;
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

  /**
   * @param shapeType The type to be converted to a SymbolKind
   * @param parentType An optional type of the shape's enclosing definition
   * @return An lsp4j SymbolKind
   */
  public static SymbolKind toSymbolKind(ShapeType shapeType, Optional<ShapeType> parentType) {
    switch (shapeType) {
      case BYTE:
      case BIG_INTEGER:
      case DOUBLE:
      case BIG_DECIMAL:
      case FLOAT:
      case LONG:
      case INTEGER:
      case SHORT:
        return SymbolKind.Number;
      case BLOB:
          // technically a sequence of bytes, so due to the lack of a better alternative, an array
      case LIST:
      case SET:
        return SymbolKind.Array;
      case BOOLEAN:
        return SymbolKind.Boolean;
      case STRING:
        return SymbolKind.String;
      case TIMESTAMP:
      case UNION:
        return SymbolKind.Interface;

      case DOCUMENT:
        return SymbolKind.Class;
      case ENUM:
      case INT_ENUM:
        return SymbolKind.Enum;
      case MAP:
        return SymbolKind.Object;
      case STRUCTURE:
        return SymbolKind.Struct;
      case MEMBER:
          if (!parentType.isPresent()) {
            return SymbolKind.Field;
          }
          switch (parentType.get()) {
              case ENUM:
                  return SymbolKind.EnumMember;
              case UNION:
                  return SymbolKind.Class;
              default: return SymbolKind.Field;
          }
      case SERVICE:
      case RESOURCE:
        return SymbolKind.Module;
      case OPERATION:
        return SymbolKind.Method;
      default:
        // This case shouldn't be reachable
        return SymbolKind.Key;
    }
  }
}
