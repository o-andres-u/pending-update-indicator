package com.caseware.pendingupdates.fanout;

import java.util.Objects;

/**
 * A unit of work: enough to call the loader, without having loaded anything.
 * Obtained from {@link EngagementDirectory} (assumption A3 -- identifiers are enumerable cheaply).
 */
public record EngagementRef(EngagementId id, String firmId, String region) {
    public EngagementRef {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(region, "region");
    }
}
