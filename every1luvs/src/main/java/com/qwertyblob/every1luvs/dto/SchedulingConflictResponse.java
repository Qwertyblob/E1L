package com.qwertyblob.every1luvs.dto;

import java.time.Instant;
import java.util.List;

/**
 * One sub-interval where the scheduling invariant is already violated: more active BOOKED
 * appointments run concurrently ({@code observedConcurrency}) than the capacity offered there
 * ({@code effectiveCapacity}), with the contributing booking and slot ids. Produced read-only by
 * the pre-deployment audit — it never cancels or mutates anything; operators resolve or grandfather
 * each conflict.
 */
public record SchedulingConflictResponse(
        Instant from,
        Instant to,
        int observedConcurrency,
        int effectiveCapacity,
        List<Long> bookingIds,
        List<Long> slotIds) {
}
