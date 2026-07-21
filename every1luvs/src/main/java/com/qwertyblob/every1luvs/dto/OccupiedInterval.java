package com.qwertyblob.every1luvs.dto;

import java.time.Duration;
import java.time.Instant;

/**
 * An active BOOKED booking projected as the technician-seat interval it occupies, used to feed
 * {@code SchedulingGuard}. The occupied interval is {@code [slotStart, slotStart + durationMin)},
 * or {@code [slotStart, slotEnd)} for legacy rows booked before duration tracking
 * ({@code durationMin} null or &le; 0). {@code durationMin} may exceed the slot length — short
 * slots are bookable by design — so the interval can end after {@code slotEnd}.
 */
public record OccupiedInterval(Long bookingId, Instant slotStart, Instant slotEnd, Integer durationMin) {

    public Instant start() {
        return slotStart;
    }

    public Instant end() {
        return (durationMin != null && durationMin > 0)
                ? slotStart.plus(Duration.ofMinutes(durationMin))
                : slotEnd;
    }
}
