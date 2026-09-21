package com.caseware.pendingupdates.fanout;

/**
 * The slow, capacity-limited dependency owned by another team: ~60 seconds per call.
 *
 * <p><b>Contract:</b> this is the only expensive call in the system, and
 * {@link TemplatePublishFanoutWorker} is its only caller, so the concurrency cap (assumption A2)
 * is enforced in exactly one place. Callers must route every invocation through a
 * {@link CapacityBudget} permit.
 *
 * <p>Implementations signal how a failure should be treated by throwing the matching
 * {@link DownstreamException} subtype. That classification is the dependency's decision, not the
 * worker's guess.
 */
public interface EngagementLoader {

    LoadedEngagement load(EngagementRef ref) throws DownstreamException, InterruptedException;
}
