/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package software.amazon.smithy.lsp.diff;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.sameInstance;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;

public class DiffEvaluatorFilterTest {

    @Test
    public void allowAllReturnsSameListUnchanged() {
        List<ValidationEvent> events = List.of(event("RemovedShape"), event("CompatValidator"));
        List<ValidationEvent> result = DiffEvaluatorFilter.allowAll().filter(events);
        assertThat(result, sameInstance(events));
    }

    @Test
    public void allowlistKeepsOnlyMatchingEvents() {
        List<ValidationEvent> events =
                List.of(event("CompatValidator"), event("RemovedShape"), event("AddedShape"));
        DiffEvaluatorFilter filter = new DiffEvaluatorFilter(List.of("CompatValidator"), List.of());
        assertThat(ids(filter.filter(events)), contains("CompatValidator"));
    }

    @Test
    public void allowlistMatchesOnDotBoundaryNotBareStringPrefix() {
        List<ValidationEvent> events = List.of(
                event("CompatValidator"),        // exact match -> kept
                event("CompatValidator.Member"), // dot-prefix match -> kept
                event("CompatValidatorX"));      // not a dot-boundary prefix -> dropped
        DiffEvaluatorFilter filter = new DiffEvaluatorFilter(List.of("CompatValidator"), List.of());
        assertThat(ids(filter.filter(events)), contains("CompatValidator", "CompatValidator.Member"));
    }

    @Test
    public void denylistSubtractsMatchingEvents() {
        List<ValidationEvent> events = List.of(event("RemovedShape"), event("CompatValidator"));
        DiffEvaluatorFilter filter = new DiffEvaluatorFilter(List.of(), List.of("RemovedShape"));
        assertThat(ids(filter.filter(events)), contains("CompatValidator"));
    }

    @Test
    public void denylistWinsOverAllowlist() {
        List<ValidationEvent> events =
                List.of(event("CompatValidator.Public"), event("CompatValidator.Internal"));
        DiffEvaluatorFilter filter =
                new DiffEvaluatorFilter(List.of("CompatValidator"), List.of("CompatValidator.Internal"));
        assertThat(ids(filter.filter(events)), contains("CompatValidator.Public"));
    }

    private static ValidationEvent event(String id) {
        return ValidationEvent.builder().id(id).severity(Severity.WARNING).message("test").build();
    }

    private static List<String> ids(List<ValidationEvent> events) {
        return events.stream().map(ValidationEvent::getId).collect(Collectors.toList());
    }
}
