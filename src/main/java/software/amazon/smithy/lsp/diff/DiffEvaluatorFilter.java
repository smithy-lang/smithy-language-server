/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.lsp.diff;

import java.util.List;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Filters diff {@link ValidationEvent}s by matching their {@linkplain ValidationEvent#getId()
 * event id} against an allowlist and a denylist
 *
 * <p>Filtering is performed on events <em>after</em> the evaluators run, not on which
 * evaluators load: an evaluator's emitted event ids cannot be known without running it (ids
 * are arbitrary, e.g. {@code ModifiedTrait.length}), so the only coherent place to filter is
 * the output. Evaluation is cheap relative to model assembly, so running all discovered
 * evaluators and discarding unwanted events is acceptable.
 *
 * <p>Matching is by dot-delimited prefix: an entry {@code "SomeValidator"} matches the event
 * ids {@code "SomeValidator"} and {@code "SomeValidator.Foo"}, but not
 * {@code "SomeValidatorX"}.
 *
 * <p>Semantics:
 * <ul>
 *   <li>If {@code enabled} is non-empty, only events matching an allowlist entry are kept.</li>
 *   <li>If {@code disabled} is non-emtpy, then subtracts any event matching the entry.</li>
 *   <li>If both lists are empty, all events pass through unchanged.</li>
 * </ul>
 */
public final class DiffEvaluatorFilter {

    private final List<String> enabled;
    private final List<String> disabled;

    /**
     * @param enabled allowlist of event-id prefixes; empty means "allow all"
     * @param disabled denylist of event-id prefixes; subtracts from the allowed set
     */
    public DiffEvaluatorFilter(List<String> enabled, List<String> disabled) {
        this.enabled = List.copyOf(enabled);
        this.disabled = List.copyOf(disabled);
    }

    /** A filter that passes every event through unchanged. */
    public static DiffEvaluatorFilter allowAll() {
        return new DiffEvaluatorFilter(List.of(), List.of());
    }

    /**
    * Filters all passed events based on the configured allow-list and deny-list.
    * @param events the list of events to filter
    */
    public List<ValidationEvent> filter(List<ValidationEvent> events) {
        if (enabled.isEmpty() && disabled.isEmpty()) {
            return events;
        }
        return events.stream().filter(this::keep).toList();
    }

    private boolean keep(ValidationEvent event) {
        String id = event.getId();
        boolean allowed = enabled.isEmpty() || enabled.stream().anyMatch(prefix -> matches(id, prefix));
        boolean denied = disabled.stream().anyMatch(prefix -> matches(id, prefix));
        return allowed && !denied;
    }

    private static boolean matches(String eventId, String prefix) {
        return eventId.equals(prefix) || eventId.startsWith(prefix + ".");
    }
}
