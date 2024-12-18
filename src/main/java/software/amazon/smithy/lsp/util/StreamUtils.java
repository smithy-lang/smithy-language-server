/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.util;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public final class StreamUtils {
    private StreamUtils() {
    }

    public static Collector<String, ?, Map<String, String>> toWrappedMap() {
        return Collectors.toMap(s -> s, s -> "\"" + s + "\"");
    }

    public static <K, U, V> Collector<Map.Entry<K, U>, ?, Map<K, V>> mappingValue(Function<U, V> valueMapper) {
        return Collectors.toMap(Map.Entry::getKey, entry -> valueMapper.apply(entry.getValue()));
    }
}
