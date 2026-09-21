package com.caseware.pendingupdates.fanout;

/**
 * Durable record of which units of a publish are finished, giving both idempotency across
 * redeliveries and resumability across restarts.
 *
 * <p><b>Deliberately not a lock.</b> There is no claim/lease protocol, so two workers handed the
 * same publish may both process a unit. That is wasteful but not incorrect, because the index write
 * is idempotent and sequence-guarded. Paying for distributed leases to save duplicate work would
 * cost more complexity than the duplicate work costs capacity.
 */
public interface ProgressStore {

    boolean isComplete(String publishId, EngagementId engagementId);

    void markComplete(String publishId, EngagementId engagementId);
}
