/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.util;

import java.util.Map;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public final class StreamUtils {
    private StreamUtils() {
    }

    public static <T, U> Collector<Map.Entry<T, U>, ?, Map<T, U>> toMap() {
        return Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    public static Collector<String, ?, Map<String, String>> toWrappedMap() {
        return Collectors.toMap(s -> s, s -> "\"" + s + "\"");
    }
}
