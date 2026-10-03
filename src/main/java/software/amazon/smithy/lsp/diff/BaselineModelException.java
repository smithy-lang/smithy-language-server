/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

/**
 * Thrown when a {@link BaselineProvider} cannot resolve or read the baseline model (as opposed
 * to the baseline assembling with validation events, which are returned, not thrown).
 */
public class BaselineModelException extends RuntimeException {

    public BaselineModelException(String message) {
        super(message);
    }

    public BaselineModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
