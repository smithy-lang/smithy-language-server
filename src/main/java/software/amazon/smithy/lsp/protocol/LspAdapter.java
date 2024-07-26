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
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.model.SourceLocation;

/**
 * Utility methods for converting to and from LSP types {@link Range}, {@link Position},
 * {@link Location} and URI (which is just a string).
 * TODO: Using a string internally for URI is pretty brittle. We could wrap it in a custom
 *  class, or try to use the {@link URI}, which has its own issues because of the
 *  'smithyjar:' scheme we use.
 */
public final class LspAdapter {
    private static final Logger LOGGER = Logger.getLogger(LspAdapter.class.getName());

    private LspAdapter() {
    }

    /**
     * @return Range of (0, 0) - (0, 0)
     */
    public static Range origin() {
        return new RangeBuilder()
                .startLine(0)
                .startCharacter(0)
                .endLine(0)
                .endCharacter(0)
                .build();
    }

    /**
     * @param point Position to create a point range of
     * @return Range of (point) - (point)
     */
    public static Range point(Position point) {
        return new Range(point, point);
    }

    /**
     * @param line Line of the point
     * @param character Character offset on the line
     * @return Range of (line, character) - (line, character)
     */
    public static Range point(int line, int character) {
        return point(new Position(line, character));
    }

    /**
     * @param line Line the span is on
     * @param startCharacter Start character of the span
     * @param endCharacter End character of the span
     * @return Range of (line, startCharacter) - (line, endCharacter)
     */
    public static Range lineSpan(int line, int startCharacter, int endCharacter) {
        return of(line, startCharacter, line, endCharacter);
    }

    /**
     * @param offset Offset from (0, 0)
     * @return Range of (0, 0) - (offset)
     */
    public static Range offset(Position offset) {
        return new RangeBuilder()
                .startLine(0)
                .startCharacter(0)
                .endLine(offset.getLine())
                .endCharacter(offset.getCharacter())
                .build();
    }

    /**
     * @param offset Offset from (offset.line, 0)
     * @return Range of (offset.line, 0) - (offset)
     */
    public static Range lineOffset(Position offset) {
        return new RangeBuilder()
                .startLine(offset.getLine())
                .startCharacter(0)
                .endLine(offset.getLine())
                .endCharacter(offset.getCharacter())
                .build();
    }

    /**
     * @param startLine Range start line
     * @param startCharacter Range start character
     * @param endLine Range end line
     * @param endCharacter Range end character
     * @return Range of (startLine, startCharacter) - (endLine, endCharacter)
     */
    public static Range of(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new RangeBuilder()
                .startLine(startLine)
                .startCharacter(startCharacter)
                .endLine(endLine)
                .endCharacter(endCharacter)
                .build();
    }

    /**
     * Get a {@link Position} from a {@link SourceLocation}, making the line/columns
     * 0-indexed.
     *
     * @param sourceLocation The source location to get the position of
     * @return The position
     */
    public static Position toPosition(SourceLocation sourceLocation) {
        return new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
    }

    /**
     * Get a {@link Location} from a {@link SourceLocation}, with the filename
     * transformed to a URI, and the line/column made 0-indexed.
     *
     * @param sourceLocation The source location to get a Location from
     * @return The equivalent Location
     */
    public static Location toLocation(SourceLocation sourceLocation) {
        return new Location(toUri(sourceLocation.getFilename()), point(
                new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1)));
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
