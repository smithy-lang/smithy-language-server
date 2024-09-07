/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.ext;

import java.util.List;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;

/**
 * A snapshot of a project the server has open.
 *
 * @param root The root URI of the project
 * @param files The list of all file URIs tracked by the project
 * @param isDetached Whether the project is detached - tracking just a single open file
 */
public record OpenProject(@NonNull String root, @NonNull List<String> files, boolean isDetached) {}
