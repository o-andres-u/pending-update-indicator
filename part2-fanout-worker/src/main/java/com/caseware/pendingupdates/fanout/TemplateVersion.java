package com.caseware.pendingupdates.fanout;

/**
 * A template version, treated as opaque by the worker.
 * <p>Ordering is deliberately NOT modelled here: versions form a branching graph, so "newer" is a
 * question for the Template Version Graph, not for this component. The worker only reports what it
 * observed; monotonicity is enforced separately via {@link LoadedEngagement#sourceSequence()}.
 */
public record TemplateVersion(String value) {
    public TemplateVersion {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("version must not be blank");
    }
    @Override public String toString() { return value; }
}
