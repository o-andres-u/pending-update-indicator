package com.caseware.pendingupdates.fanout;

import java.time.Instant;
import java.util.Objects;

/**
 * Where units go when they cannot be completed.
 *
 * <p>A publish covering ~20,000 engagements must not be held up by one bad file, so a unit that
 * exhausts its retries is parked here and the fan-out continues. Dead letters are a queue for
 * humans and a metric to alarm on, not a silent drop.
 */
public interface DeadLetterSink {

    void accept(FanoutFailure failure);

    record FanoutFailure(
            String publishId,
            EngagementRef ref,
            int attempts,
            String reason,
            String exceptionType,
            Instant at) {

        public FanoutFailure {
            Objects.requireNonNull(publishId, "publishId");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(exceptionType, "exceptionType");
            Objects.requireNonNull(at, "at");
        }
    }
}
