package com.caseware.pendingupdates.fanout;

import java.util.Objects;

/**
 * The result of the expensive (~60s) load: what template version this engagement is actually on.
 *
 * @param sourceSequence a monotonically increasing counter from the Engagement Management System,
 *                       which is the authority. The index write is conditional on it, so an
 *                       out-of-order or replayed observation can never regress newer state.
 */
public record LoadedEngagement(EngagementId id, TemplateId templateId, TemplateVersion currentVersion, long sourceSequence) {
    public LoadedEngagement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(currentVersion, "currentVersion");
        if (sourceSequence < 0) throw new IllegalArgumentException("sourceSequence must be >= 0");
    }
}
