/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidationEvent;

public class ModelDiffRunnerIsolationTest {

    private static final String EVALUATOR_FQN = "spike.ThrowingEvaluator";

    // A DiffEvaluator that always throws, compiled at test time and supplied only via a
    // URLClassLoader so ServiceLoader discovers it alongside the stock evaluators.
    private static final String EVALUATOR_SOURCE = String.join("\n",
            "package spike;",
            "import java.util.List;",
            "import software.amazon.smithy.diff.Differences;",
            "import software.amazon.smithy.diff.evaluators.AbstractDiffEvaluator;",
            "import software.amazon.smithy.model.validation.ValidationEvent;",
            "public final class ThrowingEvaluator extends AbstractDiffEvaluator {",
            "    @Override",
            "    public List<ValidationEvent> evaluate(Differences differences) {",
            "        throw new RuntimeException(\"boom\");",
            "    }",
            "}");

    @Test
    public void throwingEvaluatorIsIsolatedAndOtherEvaluatorsStillRun(@TempDir Path tempDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "A JDK (with javac) is required to build the evaluator jar");
        Path jar = buildEvaluatorJar(compiler, tempDir);

        Model oldModel = assemble("old.smithy", String.join("\n",
                "$version: \"2.0\"", "namespace example",
                "structure Foo { keep: String }", "structure Removed {}"));
        Model newModel = assemble("new.smithy", String.join("\n",
                "$version: \"2.0\"", "namespace example", "structure Foo { keep: String }"));

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()})) {
            // Must not throw, even though ThrowingEvaluator is on the classpath.
            List<ValidationEvent> events = ModelDiffRunner.run(loader, oldModel, newModel);

            // The stock RemovedShape evaluator still produced its event.
            List<String> removed = events.stream()
                    .filter(e -> e.getId().startsWith("RemovedShape"))
                    .map(e -> e.getShapeId().map(Object::toString).orElse(""))
                    .collect(Collectors.toList());
            assertThat(removed, hasItem("example#Removed"));
        }
    }

    @Test
    public void badServiceRegistrationIsSkippedAndOtherEvaluatorsStillRun(@TempDir Path tempDir) throws Exception {
        // A jar whose META-INF/services names a class that doesn't exist makes ServiceLoader throw
        // a ServiceConfigurationError while *advancing the iterator* to that provider (before any
        // provider runs). The run must survive it and still produce the stock evaluators' events.
        Path jar = tempDir.resolve("bad-evaluator.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(jos, "META-INF/services/software.amazon.smithy.diff.DiffEvaluator",
                    ("spike.DoesNotExist\n").getBytes(StandardCharsets.UTF_8));
        }

        Model oldModel = assemble("old.smithy", String.join("\n",
                "$version: \"2.0\"", "namespace example",
                "structure Foo { keep: String }", "structure Removed {}"));
        Model newModel = assemble("new.smithy", String.join("\n",
                "$version: \"2.0\"", "namespace example", "structure Foo { keep: String }"));

        try (URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()})) {
            // Must not throw, even though the bad SPI registration is on the classpath.
            List<ValidationEvent> events = ModelDiffRunner.run(loader, oldModel, newModel);

            List<String> removed = events.stream()
                    .filter(e -> e.getId().startsWith("RemovedShape"))
                    .map(e -> e.getShapeId().map(Object::toString).orElse(""))
                    .collect(Collectors.toList());
            assertThat(removed, hasItem("example#Removed"));
        }
    }

    private static Model assemble(String name, String idl) {
        return Model.assembler().addUnparsedModel(name, idl).assemble().unwrap();
    }

    private static Path buildEvaluatorJar(JavaCompiler compiler, Path tempDir) throws IOException {
        Path sourceDir = Files.createDirectories(tempDir.resolve("src").resolve("spike"));
        Path classesDir = Files.createDirectories(tempDir.resolve("classes"));
        Path sourceFile = sourceDir.resolve("ThrowingEvaluator.java");
        Files.writeString(sourceFile, EVALUATOR_SOURCE);

        int rc = compiler.run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                sourceFile.toString());
        if (rc != 0) {
            throw new IllegalStateException("Failed to compile throwing evaluator (javac rc=" + rc + ")");
        }

        Path jar = tempDir.resolve("throwing-evaluator.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(jos, "spike/ThrowingEvaluator.class",
                    Files.readAllBytes(classesDir.resolve("spike/ThrowingEvaluator.class")));
            writeEntry(jos, "META-INF/services/software.amazon.smithy.diff.DiffEvaluator",
                    (EVALUATOR_FQN + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void writeEntry(JarOutputStream jos, String name, byte[] bytes) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(bytes);
        jos.closeEntry();
    }
}
