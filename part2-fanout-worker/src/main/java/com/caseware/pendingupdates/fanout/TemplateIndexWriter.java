package com.caseware.pendingupdates.fanout;

import java.util.Optional;

/** Writes observations into the Engagement Template Index (the derived read model). */
public interface TemplateIndexWriter {

    /**
     * Records an observation, conditional on {@link LoadedEngagement#sourceSequence()} being at
     * least as new as what is already stored. This conditional write -- not the worker's
     * bookkeeping -- is what makes the pipeline safe under at-least-once delivery.
     */
    IndexWriteOutcome recordObservation(LoadedEngagement observed);

    /** Sealed so the worker handles every case; there is no "unknown write result" branch. */
    sealed interface IndexWriteOutcome {

        /**
         * Written. {@code previousVersion} is empty for a first observation (backfill) and present
         * for a re-observation (reconciliation), where a different value means the index had
         * drifted -- which is the divergence signal the SLO is measured on.
         */
        record Applied(Optional<TemplateVersion> previousVersion) implements IndexWriteOutcome {
            public Applied {
                if (previousVersion == null) throw new IllegalArgumentException("previousVersion must not be null");
            }
        }

        /**
         * Rejected as older than stored state. Not an error: it is the guard working. The unit is
         * still finished -- see the note on progress marking in the worker.
         */
        record IgnoredStale(long existingSequence) implements IndexWriteOutcome {}
    }
}
