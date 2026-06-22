/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.validation.ValidatedResult;

/**
 * Supplies the baseline ("previous") {@link Model} that the current edited model is diffed against
 *
 * <p>The abstraction is pluggable so the baseline source is not hardcoded; A provider owns its own assembly
 * and returns a fully-assembled model, so callers just cache and diff whatever they get back.
 *
 * <p>Implementations throw {@link BaselineModelException} when the baseline cannot be obtained
 * (e.g. the source cannot be resolved or read). Validation events from assembling the baseline
 * itself are carried in the returned {@link ValidatedResult} rather than thrown.
 */
public interface BaselineProvider {

    /**
     * Resolves and assembles the baseline model.
     *
     * @return the assembled baseline model and any assembly events
     * @throws BaselineModelException if the baseline cannot be resolved or read
     */
    ValidatedResult<Model> loadBaseline();
}
