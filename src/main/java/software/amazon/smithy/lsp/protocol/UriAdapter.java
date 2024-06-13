/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.protocol;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.logging.Logger;

/**
 * Utility methods for working with LSP's URI (which is just a string).
 */
public final class UriAdapter {
    private static final Logger LOGGER = Logger.getLogger(UriAdapter.class.getName());

    private UriAdapter() {
    }

    /**
     * @param uri LSP URI to convert to a path
     * @return A path representation of the {@code uri}, with the scheme removed
     */
    public static String toPath(String uri) {
        if (uri.startsWith("file://")) {
            return Paths.get(URI.create(uri)).toString();
        } else if (isSmithyJarFile(uri)) {
            String decoded = decode(uri);
            return fixJarScheme(decoded);
        }
        return uri;
    }

    /**
     * @param path Path to convert to LSP URI
     * @return A URI representation of the given {@code path}, modified to have the
     *  correct scheme for our jars
     */
    public static String toUri(String path) {
        if (path.startsWith("jar:file")) {
            return path.replaceFirst("jar:file", "smithyjar");
        } else if (path.startsWith("smithyjar:")) {
            return path;
        } else {
            return Paths.get(path).toUri().toString();
        }
    }

    /**
     * Checks if a given LSP URI is a file in a Smithy jar, which is a Smithy
     * Language Server specific file scheme (smithyjar:) used for providing
     * contents of Smithy files within Jars.
     *
     * @param uri LSP URI to check
     * @return Returns whether the uri points to a smithy file in a jar
     */
    public static boolean isSmithyJarFile(String uri) {
        return uri.startsWith("smithyjar:");
    }

    /**
     * @param uri LSP URI to check
     * @return Returns whether the uri points to a file in jar
     */
    public static boolean isJarFile(String uri) {
        return uri.startsWith("jar:");
    }

    /**
     * Get a {@link URL} for the Jar represented by the given URI or path.
     *
     * @param uriOrPath LSP URI or regular path
     * @return The {@link URL}, or throw if the uri/path cannot be decoded
     */
    public static URL jarUrl(String uriOrPath) {
        try {
            String decodedUri = decode(uriOrPath);
            return URI.create(fixJarScheme(decodedUri)).toURL();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String decode(String uriOrPath) {
        try {
            // Some clients encode parts of the jar, like !/
            return URLDecoder.decode(uriOrPath, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            LOGGER.severe("Failed to decode " + uriOrPath + " : " + e.getMessage());
            return uriOrPath;
        }
    }

    private static String fixJarScheme(String uriOrPath) {
        if (uriOrPath.startsWith("smithyjar:")) {
            uriOrPath = uriOrPath.replaceFirst("smithyjar:", "");
        }
        if (uriOrPath.startsWith("jar:")) {
            return uriOrPath;
        } else if (uriOrPath.startsWith("file:")) {
            return "jar:" + uriOrPath;
        } else {
            return "jar:file:" + uriOrPath;
        }
    }
}
