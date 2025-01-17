/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticCodeDescription;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.project.IdlFile;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectAndFile;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LspAdapter;
import software.amazon.smithy.lsp.syntax.StatementView;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Creates diagnostics for Smithy files.
 */
public final class SmithyDiagnostics {
    public static final String UPDATE_VERSION = "migrating-idl-1-to-2";
    public static final String DEFINE_VERSION = "define-idl-version";
    public static final String DETACHED_FILE = "detached-file";

    private static final DiagnosticCodeDescription UPDATE_VERSION_DESCRIPTION =
            new DiagnosticCodeDescription("https://smithy.io/2.0/guides/migrating-idl-1-to-2.html");

    private SmithyDiagnostics() {
    }

    /**
     * @param projectAndFile Project and file to get diagnostics for
     * @param minimumSeverity Minimum severity of validation events to diagnose
     * @return A list of diagnostics for the given project and file
     */
    public static List<Diagnostic> getFileDiagnostics(ProjectAndFile projectAndFile, Severity minimumSeverity) {
        if (LspAdapter.isJarFile(projectAndFile.uri()) || LspAdapter.isSmithyJarFile(projectAndFile.uri())) {
            // Don't send diagnostics to jar files since they can't be edited
            // and diagnostics could be misleading.
            return List.of();
        }

        if (!(projectAndFile.file() instanceof SmithyFile smithyFile)) {
            return List.of();
        }

        Project project = projectAndFile.project();
        String path = projectAndFile.file().path();

        EventToDiagnostic eventToDiagnostic = eventToDiagnostic(smithyFile);

        List<Diagnostic> diagnostics = project.modelResult().getValidationEvents().stream()
                .filter(event -> event.getSeverity().compareTo(minimumSeverity) >= 0
                                 && event.getSourceLocation().getFilename().equals(path))
                .map(eventToDiagnostic::toDiagnostic)
                .collect(Collectors.toCollection(ArrayList::new));

        Diagnostic versionDiagnostic = versionDiagnostic(smithyFile);
        if (versionDiagnostic != null) {
            diagnostics.add(versionDiagnostic);
        }

        if (projectAndFile.isDetached()) {
            diagnostics.add(detachedDiagnostic(smithyFile));
        }

        return diagnostics;
    }

    private static Diagnostic versionDiagnostic(SmithyFile smithyFile) {
        if (!(smithyFile instanceof IdlFile idlFile)) {
            return null;
        }

         Syntax.IdlParseResult syntaxInfo = idlFile.getParse();
        if (syntaxInfo.version().version().startsWith("2")) {
            return null;
        } else if (!LspAdapter.isEmpty(syntaxInfo.version().range())) {
            var diagnostic = createDiagnostic(
                    syntaxInfo.version().range(), "You can upgrade to idl version 2.", UPDATE_VERSION);
            diagnostic.setCodeDescription(UPDATE_VERSION_DESCRIPTION);
            return diagnostic;
        } else {
            int end = smithyFile.document().lineEnd(0);
            Range range = LspAdapter.lineSpan(0, 0, end);
            return createDiagnostic(range, "You should define a version for your Smithy file", DEFINE_VERSION);
        }
    }

    private static Diagnostic detachedDiagnostic(SmithyFile smithyFile) {
        Range range;
        if (smithyFile.document() == null) {
            range = LspAdapter.origin();
        } else {
            int end = smithyFile.document().lineEnd(0);
            range = LspAdapter.lineSpan(0, 0, end);
        }

        return createDiagnostic(range, "This file isn't attached to a project", DETACHED_FILE);
    }

    private static Diagnostic createDiagnostic(Range range, String title, String code) {
        return new Diagnostic(range, title, DiagnosticSeverity.Warning, "smithy-language-server", code);
    }

    private static EventToDiagnostic eventToDiagnostic(SmithyFile smithyFile) {
        if (!(smithyFile instanceof IdlFile idlFile)) {
            return new Simple();
        }

        var idlParse = idlFile.getParse();
        var view = StatementView.createAtStart(idlParse).orElse(null);
        if (view == null) {
            return new Simple();
        } else {
            var documentParser = DocumentParser.forStatements(smithyFile.document(), view.parseResult().statements());
            return new Idl(view, documentParser);
        }
    }

    private sealed interface EventToDiagnostic {
        default Range getDiagnosticRange(ValidationEvent event) {
            var start = LspAdapter.toPosition(event.getSourceLocation());
            var end = LspAdapter.toPosition(event.getSourceLocation());
            end.setCharacter(end.getCharacter() + 1); // Range is exclusive

            return new Range(start, end);
        }

        default Diagnostic toDiagnostic(ValidationEvent event) {
            var diagnosticSeverity = switch (event.getSeverity()) {
                case ERROR, DANGER -> DiagnosticSeverity.Error;
                case WARNING -> DiagnosticSeverity.Warning;
                case NOTE -> DiagnosticSeverity.Information;
                default -> DiagnosticSeverity.Hint;
            };
            var diagnosticRange = getDiagnosticRange(event);
            var message = event.getId() + ": " + event.getMessage();
            return new Diagnostic(diagnosticRange, message, diagnosticSeverity, "Smithy");
        }
    }

    private record Simple() implements EventToDiagnostic {}

    private record Idl(StatementView view, DocumentParser parser) implements EventToDiagnostic {
        @Override
        public Range getDiagnosticRange(ValidationEvent event) {
            Position eventStart = LspAdapter.toPosition(event.getSourceLocation());
            final Range defaultRange = EventToDiagnostic.super.getDiagnosticRange(event);

            if (event.getShapeId().isPresent()) {
                int eventStartIndex = parser.getDocument().indexOfPosition(eventStart);
                Syntax.Statement statement = view.getStatementAt(eventStartIndex).orElse(null);

                if (statement instanceof Syntax.Statement.MemberDef def
                    && event.containsId("Target")
                    && def.target() != null) {
                    Range targetRange = LspAdapter.identRange(def.target(), parser.getDocument());
                    return Objects.requireNonNullElse(targetRange, defaultRange);
                } else if (statement instanceof Syntax.Statement.TraitApplication app) {
                    Range traitIdRange = LspAdapter.identRange(app.id(), parser.getDocument());
                    if (traitIdRange != null) {
                        traitIdRange.getStart().setCharacter(traitIdRange.getStart().getCharacter() - 1); // include @
                    }
                    return Objects.requireNonNullElse(traitIdRange, defaultRange);
                }
            }

            return Objects.requireNonNullElse(parser.findContiguousRange(event.getSourceLocation()), defaultRange);
        }
    }
}
