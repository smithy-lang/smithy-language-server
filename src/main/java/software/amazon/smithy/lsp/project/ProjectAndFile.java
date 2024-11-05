/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.project;

/**
 * Simple wrapper for a project and a file in that project, which many
 * server functions act upon.
 *
 * @param project The project, non-nullable
 * @param file The file within {@code project}, non-nullable
 */
public record ProjectAndFile(Project project, ProjectFile file) {
}
