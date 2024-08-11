/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.ext;

import java.util.List;
import org.eclipse.lsp4j.jsonrpc.validation.NonNull;

/**
 * A snapshot of the server status, containing the projects it has open.
 * We can add more here later as we see fit.
 *
 * @param openProjects The open projects tracked by the server
 */
public record ServerStatus(@NonNull List<OpenProject> openProjects) {}
