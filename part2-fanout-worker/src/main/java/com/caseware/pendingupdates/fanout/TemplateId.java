package com.caseware.pendingupdates.fanout;

/** Identifier of a product template. Distinct type so it cannot be confused with an engagement id. */
public record TemplateId(String value) {
    public TemplateId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("templateId must not be blank");
    }
    @Override public String toString() { return value; }
}
