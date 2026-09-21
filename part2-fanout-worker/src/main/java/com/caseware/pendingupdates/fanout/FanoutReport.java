package com.caseware.pendingupdates.fanout;

import java.time.Duration;

/**
 * Outcome of one fan-out. Every unit is accounted for in exactly one terminal bucket, so
 * {@code enumerated == skipped + completed + deadLettered + abandoned} always holds -- a report
 * that does not balance means units were lost, which is the failure mode worth alarming on.
 */
public record FanoutReport(
        String publishId,
        long enumerated,
        long skipped,
        long completed,
        long diverged,
        long staleIgnored,
        long retried,
        long deadLettered,
        long abandoned,
        boolean cancelled,
        boolean workListExhausted,
        Duration elapsed) {

    /** True when every enumerated unit reached a terminal state. */
    public boolean balanced() {
        return enumerated == skipped + completed + deadLettered + abandoned;
    }

    public String summary() {
        return ("publish=%s enumerated=%d skipped=%d completed=%d (diverged=%d staleIgnored=%d) "
                + "retried=%d deadLettered=%d abandoned=%d cancelled=%s exhausted=%s elapsed=%dms balanced=%s")
                .formatted(publishId, enumerated, skipped, completed, diverged, staleIgnored,
                        retried, deadLettered, abandoned, cancelled, workListExhausted,
                        elapsed.toMillis(), balanced());
    }
}
