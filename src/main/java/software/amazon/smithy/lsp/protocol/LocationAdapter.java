/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.protocol;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import software.amazon.smithy.model.SourceLocation;

/**
 * Utility methods for working with LSP's {@link Location}.
 */
public final class LocationAdapter {
    private LocationAdapter() {
    }

    /**
     * Get a {@link Location} from a {@link SourceLocation}, with the filename
     * transformed to a URI, and the line/column made 0-indexed.
     *
     * @param sourceLocation The source location to get a Location from
     * @return The equivalent Location
     */
    public static Location fromSource(SourceLocation sourceLocation) {
        return new Location(UriAdapter.toUri(sourceLocation.getFilename()), RangeAdapter.point(
                new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1)));
    }
}
