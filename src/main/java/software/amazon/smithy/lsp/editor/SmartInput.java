/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.editor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

public final class SmartInput {
    public static final Position POS_0_0 = new Position(0, 0);
    private final String input;
    private final Range range;

    private SmartInput(List<String> lines) {
        Position endPos;
        if (lines.isEmpty()) {
            endPos = POS_0_0;
        } else {
            final int lastLineI = lines.size() - 1;
            final String lastLine = lines.get(lastLineI);
            endPos = new Position(lastLineI, lastLine.length());
        }
        this.input = String.join("\n", lines);
        this.range = new Range(POS_0_0, endPos);
    }

    /**
     * Read the file at `p`.
     * @param p path to the file to read
     * @return the content if no exception occurs, otherwise throws.
     */
    public static SmartInput fromPath(Path p) throws IOException {
        return fromInput(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    /**
     * Read the file at `p` and wrap it into an Optional.
     * @param p path to the file to read
     * @return Optional with the content if no exception occurs, otherwise Optional.empty.
     */
    public static Optional<SmartInput> fromPathSafe(Path p) {
        try {
            return Optional.of(fromPath(p));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static SmartInput fromInput(String input) {
        String[] split = input.split("\\n", -1); // keep trailing new lines
        return new SmartInput(Arrays.asList(split));
    }

    public String getInput() {
        return input;
    }

    public Range getRange() {
        return range;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SmartInput that = (SmartInput) o;
        return input.equals(that.input) && range.equals(that.range);
    }

    @Override
    public int hashCode() {
        return Objects.hash(input, range);
    }

    @Override
    public String toString() {
        return "SmartInput{" + "input='" + input + '\'' + ", range=" + range + '}';
    }
}
