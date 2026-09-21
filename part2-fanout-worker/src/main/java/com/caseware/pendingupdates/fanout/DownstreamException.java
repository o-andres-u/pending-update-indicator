package com.caseware.pendingupdates.fanout;

import java.time.Duration;
import java.util.Objects;

/**
 * Failure taxonomy for the load dependency. Sealed, so the retry decision is a total function over
 * three cases rather than string-matching on error messages.
 */
public abstract sealed class DownstreamException extends Exception {

    private static final long serialVersionUID = 1L;

    protected DownstreamException(String message) { super(message); }
    protected DownstreamException(String message, Throwable cause) { super(message, cause); }

    /** Worth retrying: timeout, connection reset, transient 5xx. */
    public static final class Transient extends DownstreamException {
        private static final long serialVersionUID = 1L;
        public Transient(String message) { super(message); }
        public Transient(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Not worth retrying: engagement deleted, malformed, permanently inaccessible.
     * Dead-lettered on the first attempt -- retrying would spend a minute of scarce capacity to
     * fail identically.
     */
    public static final class Permanent extends DownstreamException {
        private static final long serialVersionUID = 1L;
        public Permanent(String message) { super(message); }
        public Permanent(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * The dependency is over capacity and told us to back off. Distinct from {@link Transient}
     * because the correct response is <em>global</em>: every in-flight unit must slow down, since
     * they all share one downstream. Retrying this unit alone would keep hammering it.
     */
    public static final class Overloaded extends DownstreamException {
        private static final long serialVersionUID = 1L;
        private final Duration retryAfter;

        public Overloaded(String message, Duration retryAfter) {
            super(message);
            this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
            if (retryAfter.isNegative()) throw new IllegalArgumentException("retryAfter must not be negative");
        }

        public Duration retryAfter() { return retryAfter; }
    }
}
