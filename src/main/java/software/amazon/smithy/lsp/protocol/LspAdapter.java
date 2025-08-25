/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.protocol;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.syntax.Syntax;
import software.amazon.smithy.model.FromSourceLocation;
import software.amazon.smithy.model.SourceLocation;

/**
 * Utility methods for converting to and from LSP types {@link Range}, {@link Position},
 * {@link Location} and URI (which is just a string).
 * TODO: Using a string internally for URI is pretty brittle. We could wrap it in a custom
 *  class, or try to use the {@link URI}, which has its own issues because of the
 *  'smithyjar:' scheme we use.
 */
public final class LspAdapter {
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
     * @param ident Identifier to get the range of
     * @param document Document the identifier is in
     * @return The range of the identifier in the given document
     */
    public static Range identRange(Syntax.Ident ident, Document document) {
        return document.rangeOfValue(ident);
    }

    /**
     * @param range The range to check
     * @return Whether the range's start is equal to it's end
     */
    public static boolean isEmpty(Range range) {
        return range.getStart().equals(range.getEnd());
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
     * @param fromSourceLocation The source location to get a Location from
     * @return The equivalent Location
     */
    public static Location toLocation(FromSourceLocation fromSourceLocation) {
        SourceLocation sourceLocation = fromSourceLocation.getSourceLocation();
        return new Location(toUri(sourceLocation.getFilename()), point(toPosition(sourceLocation)));
    }

    /**
     * Get a {@link SourceLocation} with the given path, at the start of the given
     * range.
     *
     * @param path The path of the source location
     * @param range The range of the source location
     * @return The source location
     */
    public static SourceLocation toSourceLocation(String path, Range range) {
        return toSourceLocation(path, range.getStart());
    }

    /**
     * Get a {@link SourceLocation} with the given path, at the given position.
     *
     * @param path The path of the source location
     * @param position The position of the source location
     * @return The source location
     */
    public static SourceLocation toSourceLocation(String path, Position position) {
        return new SourceLocation(
                path,
                position.getLine() + 1,
                position.getCharacter() + 1
        );
    }

    /**
     * @param uri LSP URI to convert to a path
     * @return A path representation of the {@code uri}, with the scheme removed
     */
    public static String toPath(String uri) {
        if (uri.startsWith("file:")) {
            return Paths.get(URI.create(uri)).toString();
        } else if (isSmithyJarFile(uri)) {
            URI jarUri = smithyJarUriToJarModelFilename(uri);
            return jarUri.toString();
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
     * Get a {@link URL} for the Jar represented by the given smithyjar URI.
     *
     * @param uri The smithyjar URI
     * @return A URL which can be used to read the contents of the file
     */
    public static URL smithyJarUriToReadableUrl(String uri) {
        try {
            URI jarUri = smithyJarUriToJarModelFilename(uri);
            return jarUri.toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a {@link URL} for the jar file from the filename in the model's
     * source location.
     *
     * @param modelFilename The filename from the model's source location
     * @return A URL which can be used to read the contents of the file
     */
    public static URL jarModelFilenameToReadableUrl(String modelFilename) {
        try {
            // No need to decode these, they're already encoded
            return URI.create(modelFilename).toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts a smithyjar uri that was sent from the client into a URI that
     * is equivalent to what appears in the Smithy model.
     *
     * @param smithyJarUri smithyjar uri received from the client
     * @return The converted URI
     */
    private static URI smithyJarUriToJarModelFilename(String smithyJarUri) {
        // Clients encode URIs differently. VSCode is particularly aggressive with
        // its encoding, so the URIs it produces aren't equivalent to what we get
        // from source locations in the model.
        //
        // For example, given a jar that lives in some directory with special characters:
        //  /path with spaces/foo.jar
        // The model will have a source location with a filename like:
        //  jar:file:/path%20with%20spaces/foo.jar!/baz.smithy
        // When sending requests/notifications for this file, VSCode will encode the URI like:
        //  smithyjar:/path%20with%20spaces/foo.jar%21/baz.smithy
        // Note the ! is encoded.
        //
        // If we just used URI.create().toString(), we will end up with the exact same
        // URI that VSCode sent, because URI.create() (and its equivalent ctor) keep
        // the original input string to use for the toString() call.
        //
        // Instead, we use getSchemeSpecificPart() to fully decode everything after the
        // smithyjar: part, to get:
        //  /path with spaces/foo.jar!/baz.smithy
        // Then, we reconstruct the URI from parts, using a different ctor that performs
        // encoding. The resulting URI.toString() call will give us what we want:
        //  jar:file:/path%20with%20spaces/foo.jar!/baz.smithy

        URI encodedUri = URI.create(smithyJarUri);
        String decodedPath = encodedUri.getSchemeSpecificPart();

        try {
            return new URI("jar", "file:" + decodedPath, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
