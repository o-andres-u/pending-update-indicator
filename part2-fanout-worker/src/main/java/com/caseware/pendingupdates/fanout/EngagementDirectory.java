package com.caseware.pendingupdates.fanout;

import java.util.stream.Stream;

/** Enumerates the work list for a publish. */
public interface EngagementDirectory {

    /**
     * Engagements created from the given template, as a <em>lazy</em> stream.
     * <p>Laziness is part of the contract, not an optimisation: a single publish can cover ~20,000
     * engagements and one firm alone can hold ~40,000, so the worker must never materialise the
     * work list. The returned stream is closed by the caller.
     */
    Stream<EngagementRef> engagementsUsing(TemplateId templateId);
}
