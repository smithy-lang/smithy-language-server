/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.lsp.ext;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.utils.ListUtils;

/**
 * This log interface buffers the messages until the server receives information
 * about workspace root.
 * <p>
 * This places the log messages into a file in the workspace, rather than
 * pre-defined location on filesystem.
 */
public final class LspLog {
    private static FileWriter fw = null;
    private static Optional<List<Object>> buffer = Optional.of(new ArrayList<>());
    private static boolean enabled = false;

    private LspLog() {
    }

    /**
     * Clears out the buffered messages.
     */
    public static void clearBuffer() {
        synchronized (buffer) {
            buffer.ifPresent(List::clear);
        }
    }

    /**
     * Produces a snapshot of the current log buffer.
     *
     * @return a copy of the messages currently in the buffer
     */
    public static List<Object> getBuffer() {
        return ListUtils.copyOf(buffer.orElse(Collections.emptyList()));
    }

    /**
     * Sets workspace folder for the logger.
     * <p>
     * All the pending messages in the buffer will be flushed to the file created in
     * the workspace
     *
     * @param folder workspace folder where log file will be created
     */
    public static void setWorkspaceFolder(File folder) {
        if (!enabled) {
            return;
        }

        try {
            fw = new FileWriter(Paths.get(folder.getAbsolutePath(), "/.smithy.lsp.log").toFile());
            synchronized (buffer) {
                buffer.ifPresent(buf -> buf.forEach(LspLog::println));
                buffer = Optional.empty();
            }
        } catch (IOException e) {
            // TODO: handle exception
        }

    }

    /**
     * Enables writing LspLog to a file.
     */
    public static void enable() {
        enabled = true;
    }

    private static String currentTime() {
        return LocalTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_TIME);
    }

    /**
     * Write a line to the log.
     *
     * @param message object to write, will be converted to String
     */
    public static void println(Object message) {
        if (!enabled) {
            return;
        }
        String sanitizedMessage = getStringifiedMessage(message);
        String timestamped = "[" + currentTime() + "] " + sanitizedMessage;
        try {
            if (fw != null) {
                fw.append(timestamped + "\n").flush();
            } else {
                synchronized (buffer) {
                    buffer.ifPresent(buf -> buf.add(sanitizedMessage));
                }
            }
        } catch (Exception e) {

        }
    }

    private static String getStringifiedMessage(Object message) {
        if (message == null) {
            return "null";
        } else if (message instanceof Throwable) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ((Throwable) message).printStackTrace(pw);
            return sw.toString();
        } else {
            return message.toString();
        }
    }
}
