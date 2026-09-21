package com.caseware.pendingupdates.fanout;

/** Identifier of a single engagement file. */
public record EngagementId(String value) {
    public EngagementId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("engagementId must not be blank");
    }
    @Override public String toString() { return value; }
}
