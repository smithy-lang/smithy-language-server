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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;

public final class Utils {
    private Utils() {

    }

    /**
     * @param value Value to be used.
     * @param <U>   Type of Value.
     * @return Returns the value of a specific type as a CompletableFuture.
     */
    public static <U> CompletableFuture<U> completableFuture(U value) {
        Supplier<U> supplier = () -> value;

        return CompletableFuture.supplyAsync(supplier);
    }

    /**
     * @param rawUri String
     * @return Returns whether the uri points to a file in jar.
     * @throws IOException when rawUri cannot be URL-decoded
     */
    public static boolean isSmithyJarFile(String rawUri) throws IOException {
        try {
            String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
            return uri.startsWith("smithyjar:");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * @param uri String
     * @return Returns whether the uri points to a file in jar.
     */
    public static boolean isJarFile(String uri) {
        return uri.startsWith("jar:");
    }

    /**
     * @param uri String
     * @return Remove the jar:file: part and replace it with "smithyjar"
     */
    public static String toSmithyJarFile(String uri) {
        return "smithyjar:" + uri.substring(9);
    }

    /**
     * @param rawUri String
     * @return Returns whether the uri points to a file in the filesystem (as
     * opposed to a file in a jar).
     */
    public static boolean isFile(String rawUri) {
        try {
            String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
            return uri.startsWith("file:");
        } catch (IOException e) {
            return false;
        }
    }

    private static List<String> getLines(InputStream is) throws IOException {
        List<String> result = null;
        try {
            if (is != null) {
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader reader = new BufferedReader(isr);
                result = reader.lines().collect(Collectors.toList());
            }
        } finally {
            is.close();
        }

        return result;
    }

    /**
     * @param rawUri the uri to a file in a jar.
     * @return the lines of the file in a jar
     * @throws IOException when rawUri cannot be URI-decoded.
     */
    public static List<String> jarFileContents(String rawUri) throws IOException {
        String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
        String[] pathArray = uri.split("!/");
        String jarPath = Utils.jarPath(rawUri);
        String file = pathArray[1];

        try (JarFile jar = new JarFile(new File(jarPath))) {
            ZipEntry entry = jar.getEntry(file);

            return getLines(jar.getInputStream(entry));
        }
    }

    /**
     * Extracts just the .jar part from a URI.
     *
     * @param rawUri URI of a symbol/file in a jar
     * @return Jar path
     * @throws UnsupportedEncodingException when rawUri cannot be URL-decoded
     */
    public static String jarPath(String rawUri) throws UnsupportedEncodingException {
        String uri = java.net.URLDecoder.decode(rawUri, StandardCharsets.UTF_8.name());
        if (uri.startsWith("smithyjar:")) {
            uri = uri.replaceFirst("smithyjar:", "");
        }
        String[] pathArray = uri.split("!/");
        return pathArray[0];
    }


    /**
     * Read only the first N lines of a file.
     * @param file file to read
     * @param n number of lines to read, must be >= 0. if n is 3, we'll return lines 0, 1, 2
     * @return list of numbered lines, empty if the file does not exist or
     * is empty.
     */
    public static List<NumberedLine> readFirstNLines(File file, int n) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("n must be greater or equal to 0");
        }

        Path filePath = file.toPath();
        if (!Files.exists(filePath)) {
            return Collections.emptyList();
        }

        final ArrayList<NumberedLine> list = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            reader.lines().limit(n).forEach(s -> list.add(new NumberedLine(s, list.size())));
        }
        return list;

    }

    /**
     * Given a content, split it on new line and extract the first n lines.
     * @param content content to look at
     * @param n number of lines to extract
     * @return list of numbered lines, empty if the content has no newline in it.
     */
    public static List<NumberedLine> contentFirstNLines(String content, int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be greater or equal to 0");
        }

        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }

        String[] contentLines = content.split("\n");

        if (contentLines.length == 0) {
            return Collections.emptyList();
        }

        return IntStream.range(0, Math.min(n, contentLines.length))
                 .mapToObj(i -> new NumberedLine(contentLines[i], i))
                 .collect(Collectors.toList());
    }

    public static class NumberedLine {
        private final String content;
        private final int lineNumber;

        NumberedLine(String content, int lineNumber) {
            this.content = content;
            this.lineNumber = lineNumber;
        }

        public String getContent() {
            return content;
        }

        public int getLineNumber() {
            return lineNumber;
        }
    }

    /**
     * Helper to provide an alternative Optional if the first is empty.
     * @param o1 first optional
     * @param o2get supplier to retrieve the second optional
     * @return the first optional if not empty, otherwise get the second optional
     */
    public static <T> Optional<T> optOr(Optional<T> o1, Supplier<Optional<T>> o2get) {
        if (o1.isPresent()) {
            return o1;
        } else {
            return o2get.get();
        }
    }
}
