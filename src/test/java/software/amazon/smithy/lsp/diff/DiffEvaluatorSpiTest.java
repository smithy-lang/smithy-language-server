/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.smithy.diff.DiffEvaluator;
import software.amazon.smithy.diff.ModelDiff;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Guards the load-bearing assumption behind in-process DiffEvaluator support (see
 * docs/design/diff-evaluators/0001-execution-model.md and 0008-derisking-spike.md): a custom
 * {@link DiffEvaluator} provided as a project dependency jar is discovered via the SPI
 * {@link java.util.ServiceLoader} and run by {@link ModelDiff}, when loaded through the same
 * classloader wiring the language server uses for project dependencies
 * ({@code new URLClassLoader(urls)} in {@code ProjectLoader.createModelAssemblerFactory}).
 *
 * <p>The evaluator is compiled at test time against the repo's current Smithy version and
 * supplied <em>only</em> through the {@code URLClassLoader} — never on the test's own
 * classpath — so this genuinely exercises cross-classloader SPI discovery rather than trivial
 * same-classpath loading.
 */
public class DiffEvaluatorSpiTest {

    private static final String EVALUATOR_PACKAGE = "spike";
    private static final String EVALUATOR_SIMPLE_NAME = "RemovedShapeSpikeEvaluator";
    private static final String EVALUATOR_FQN = EVALUATOR_PACKAGE + "." + EVALUATOR_SIMPLE_NAME;

    // A trivial stand-in for CompatValidator: flags every removed shape as a DANGER event.
    private static final String EVALUATOR_SOURCE = String.join("\n",
            "package " + EVALUATOR_PACKAGE + ";",
            "import java.util.List;",
            "import java.util.stream.Collectors;",
            "import software.amazon.smithy.diff.Differences;",
            "import software.amazon.smithy.diff.evaluators.AbstractDiffEvaluator;",
            "import software.amazon.smithy.model.validation.ValidationEvent;",
            "public final class " + EVALUATOR_SIMPLE_NAME + " extends AbstractDiffEvaluator {",
            "    @Override",
            "    public List<ValidationEvent> evaluate(Differences differences) {",
            "        return differences.removedShapes()",
            "            .map(shape -> danger(shape, \"SPIKE: removed shape `\" + shape.getId() + \"`\"))",
            "            .collect(Collectors.toList());",
            "    }",
            "}");

    @Test
    public void customEvaluatorIsDiscoveredViaSpiThroughProjectClassloader(@TempDir Path tempDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        // ToolProvider returns null on a JRE; the Gradle toolchain runs on a JDK, but be safe.
        assumeTrue(compiler != null, "A JDK (with javac) is required to build the evaluator jar");

        Path evaluatorJar = buildEvaluatorJar(compiler, tempDir);

        Model oldModel = assemble("old.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String, dropped: String }",
                "structure Removed {}"));
        Model newModel = assemble("new.smithy", String.join("\n",
                "$version: \"2.0\"",
                "namespace example",
                "structure Foo { keep: String }"));

        // Exactly mirrors ProjectLoader.createModelAssemblerFactory: the dependency jar is
        // reachable only via this URLClassLoader, whose default parent is the application
        // classloader that holds the server's bundled Smithy (parent-first delegation).
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {evaluatorJar.toUri().toURL()})) {
            // ModelDiff itself must resolve from the bundled Smithy, not the dependency jar.
            assertThat(
                    ModelDiff.class.getClassLoader(),
                    equalTo(DiffEvaluatorSpiTest.class.getClassLoader()));

            List<ValidationEvent> events = ModelDiff.compare(classLoader, oldModel, newModel);

            List<ValidationEvent> spikeEvents = events.stream()
                    .filter(e -> e.getId().startsWith(EVALUATOR_SIMPLE_NAME))
                    .collect(Collectors.toList());

            // Removed `Removed` shape and removed `Foo$dropped` member -> two DANGER events.
            assertThat(spikeEvents, hasSize(2));
            List<String> ids = spikeEvents.stream()
                    .map(e -> e.getShapeId().map(Object::toString).orElse(""))
                    .collect(Collectors.toList());
            assertThat(ids, hasItem("example#Removed"));
            assertThat(ids, hasItem("example#Foo$dropped"));
            spikeEvents.forEach(e -> assertThat(e.getSeverity(), equalTo(Severity.DANGER)));
        }
    }

    private static Model assemble(String name, String idl) {
        return Model.assembler().addUnparsedModel(name, idl).assemble().unwrap();
    }

    /** Compiles the evaluator against the bundled Smithy and packages it with SPI registration. */
    private static Path buildEvaluatorJar(JavaCompiler compiler, Path tempDir) throws IOException {
        Path sourceDir = Files.createDirectories(tempDir.resolve("src").resolve(EVALUATOR_PACKAGE));
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        Path sourceFile = sourceDir.resolve(EVALUATOR_SIMPLE_NAME + ".java");
        Files.writeString(sourceFile, EVALUATOR_SOURCE);

        String compileClasspath = smithyCompileClasspath();
        int rc = compiler.run(null, null, null,
                "-cp", compileClasspath,
                "-d", classesDir.toString(),
                sourceFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("Failed to compile test evaluator (javac rc=" + rc + ")");
        }

        Path jar = tempDir.resolve("spike-evaluator.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            // The compiled class.
            String classEntry = EVALUATOR_PACKAGE + "/" + EVALUATOR_SIMPLE_NAME + ".class";
            writeEntry(jos, classEntry, Files.readAllBytes(classesDir.resolve(classEntry)));
            // The SPI registration that makes ServiceLoader find it.
            writeEntry(jos,
                    "META-INF/services/software.amazon.smithy.diff.DiffEvaluator",
                    (EVALUATOR_FQN + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(bytes);
        jos.closeEntry();
    }

    /**
     * Compiles against the full test classpath (populated by Gradle with every Smithy jar),
     * so the evaluator builds against the repo's current Smithy version and tracks version
     * bumps automatically. The compiled class is only ever placed in the jar loaded via
     * {@link URLClassLoader}, so SPI discovery is still genuinely exercised.
     */
    private static String smithyCompileClasspath() {
        String cp = System.getProperty("java.class.path");
        if (cp == null || cp.isBlank()) {
            throw new IllegalStateException("java.class.path is empty; cannot compile the test evaluator");
        }
        return cp;
    }
}
