package com.caseware.pendingupdates.fanout;

import java.time.Instant;
import java.util.Objects;

/**
 * A template publish that triggers a fan-out.
 *
 * @param publishId stable, caller-supplied identity of this publish. It is the idempotency key:
 *                  redelivering the same publishId must not redo completed work. Because upstream
 *                  delivery is at-least-once, this arriving twice is expected, not exceptional.
 */
public record PublishEvent(String publishId, TemplateId templateId, TemplateVersion newVersion, Instant publishedAt) {
    public PublishEvent {
        if (publishId == null || publishId.isBlank()) throw new IllegalArgumentException("publishId must not be blank");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(newVersion, "newVersion");
        Objects.requireNonNull(publishedAt, "publishedAt");
    }
}
