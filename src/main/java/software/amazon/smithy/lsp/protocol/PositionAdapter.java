/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.protocol;

import org.eclipse.lsp4j.Position;
import software.amazon.smithy.model.SourceLocation;

/**
 * Utility methods for working with LSP's {@link Position}.
 */
public final class PositionAdapter {
    private PositionAdapter() {
    }

    /**
     * Get a {@link Position} from a {@link SourceLocation}, making the line/columns
     * 0-indexed.
     *
     * @param sourceLocation The source location to get the position of
     * @return The position
     */
    public static Position fromSourceLocation(SourceLocation sourceLocation) {
        return new Position(sourceLocation.getLine() - 1, sourceLocation.getColumn() - 1);
    }
}
