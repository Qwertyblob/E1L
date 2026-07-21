package com.qwertyblob.every1luvs.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The single authority for the scheduling invariant: at every instant, the number of active
 * appointments running at once must not exceed the capacity offered at that instant.
 *
 * <p>Pure and side-effect-free — it takes appointment intervals and capacity tiles and answers
 * questions about them; it never touches the database. Callers ({@code BookingService},
 * {@code SlotService}) load the current state under the scheduling lock and feed it here, so the
 * "count vs. concurrency" trap and the "which capacity applies" ambiguity are resolved in exactly
 * one place.
 *
 * <p><b>Model.</b> An appointment occupies {@code [start, end)} (half-open, so back-to-back
 * appointments are not concurrent). Capacity at an instant {@code t} is the <em>minimum</em>
 * {@code capacity} over active tiles covering {@code t}; if no tile covers {@code t}, capacity is
 * unconstrained. The check is evaluated by a sweep over the elementary sub-intervals cut by every
 * appointment/tile boundary clipped to the query window.
 */
@Component
public class SchedulingGuard {

    /** An active appointment occupying {@code [start, end)}. {@code bookingId} may be null in tests/ad-hoc checks. */
    public record Appointment(Instant start, Instant end, Long bookingId) {
    }

    /** A slot offering {@code capacity} concurrent seats over {@code [start, end)}. */
    public record CapacityTile(Instant start, Instant end, int capacity, Long slotId) {
    }

    /** A sub-interval where existing concurrency exceeds the effective capacity, with the culprits. */
    public record Violation(Instant from, Instant to, int concurrency, int capacity,
                            List<Long> bookingIds, List<Long> slotIds) {
    }

    /**
     * True iff adding one appointment occupying {@code [ws, we)} keeps concurrency within capacity
     * at every instant of that window (given the currently-existing appointments and tiles).
     */
    public boolean canAddAppointment(Instant ws, Instant we,
                                     List<Appointment> existing, List<CapacityTile> tiles) {
        requireValidWindow(ws, we);
        for (Segment seg : sweep(ws, we, existing, tiles)) {
            // The new appointment covers the whole window, so it adds 1 everywhere in [ws, we).
            if (seg.capacity != null && seg.concurrency + 1 > seg.capacity) {
                return false;
            }
        }
        return true;
    }

    /**
     * True iff the {@code proposedTiles} still cover the existing appointments' concurrency at every
     * instant of {@code [ws, we)}. Pass the <em>complete</em> proposed tile set (the slot being
     * created/updated included exactly once, its old version excluded).
     */
    public boolean capacityChangeIsSafe(List<Appointment> existing, List<CapacityTile> proposedTiles,
                                        Instant ws, Instant we) {
        requireValidWindow(ws, we);
        for (Segment seg : sweep(ws, we, existing, proposedTiles)) {
            // No +1: we are changing capacity, not adding an appointment.
            if (seg.capacity != null && seg.concurrency > seg.capacity) {
                return false;
            }
        }
        return true;
    }

    /**
     * Validation-only audit: every sub-interval across all given appointments where concurrency
     * already exceeds capacity, with the contributing booking and slot ids. Never mutates anything.
     */
    public List<Violation> findViolations(List<Appointment> appointments, List<CapacityTile> tiles) {
        if (appointments.isEmpty()) {
            return List.of();
        }
        Instant ws = appointments.stream().map(Appointment::start).min(Instant::compareTo).orElseThrow();
        Instant we = appointments.stream().map(Appointment::end).max(Instant::compareTo).orElseThrow();
        List<Violation> violations = new ArrayList<>();
        for (Segment seg : sweep(ws, we, appointments, tiles)) {
            if (seg.capacity != null && seg.concurrency > seg.capacity) {
                violations.add(new Violation(seg.from, seg.to, seg.concurrency, seg.capacity,
                        seg.bookingIds, seg.slotIds));
            }
        }
        return violations;
    }

    // ── internals ────────────────────────────────────────────────────────────────────────────

    private record Segment(Instant from, Instant to, int concurrency, Integer capacity,
                           List<Long> bookingIds, List<Long> slotIds) {
    }

    /**
     * Cut {@code [ws, we)} at every appointment/tile boundary (clipped into the window so intervals
     * that begin before {@code ws} but are still active at {@code ws} contribute) and describe each
     * elementary sub-interval. Coverage is half-open: an interval covers {@code [a, b)} iff
     * {@code start <= a && end >= b}, which makes abutting intervals (end == next start)
     * non-overlapping without any explicit event ordering.
     */
    private List<Segment> sweep(Instant ws, Instant we, List<Appointment> appts, List<CapacityTile> tiles) {
        TreeSet<Instant> points = new TreeSet<>();
        points.add(ws);
        points.add(we);
        for (Appointment a : appts) {
            addClippedBoundaries(points, a.start(), a.end(), ws, we);
        }
        for (CapacityTile t : tiles) {
            addClippedBoundaries(points, t.start(), t.end(), ws, we);
        }

        List<Instant> ordered = new ArrayList<>(points);
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i + 1 < ordered.size(); i++) {
            Instant a = ordered.get(i);
            Instant b = ordered.get(i + 1);

            List<Long> bookingIds = new ArrayList<>();
            for (Appointment ap : appts) {
                if (covers(ap.start(), ap.end(), a, b)) {
                    bookingIds.add(ap.bookingId());
                }
            }

            Integer capacity = null;
            List<Long> slotIds = new ArrayList<>();
            for (CapacityTile t : tiles) {
                if (covers(t.start(), t.end(), a, b)) {
                    capacity = (capacity == null) ? t.capacity() : Math.min(capacity, t.capacity());
                    slotIds.add(t.slotId());
                }
            }

            segments.add(new Segment(a, b, bookingIds.size(), capacity, bookingIds, slotIds));
        }
        return segments;
    }

    private void addClippedBoundaries(TreeSet<Instant> points, Instant start, Instant end,
                                      Instant ws, Instant we) {
        Instant clippedStart = start.isAfter(ws) ? start : ws;
        Instant clippedEnd = end.isBefore(we) ? end : we;
        if (clippedStart.isBefore(clippedEnd)) {
            points.add(clippedStart);
            points.add(clippedEnd);
        }
    }

    // An interval [start, end) covers the elementary sub-interval [a, b) iff start <= a and end >= b.
    private boolean covers(Instant start, Instant end, Instant a, Instant b) {
        return !start.isAfter(a) && !end.isBefore(b);
    }

    private void requireValidWindow(Instant ws, Instant we) {
        if (ws == null || we == null || !ws.isBefore(we)) {
            throw new IllegalArgumentException("Invalid scheduling window: [" + ws + ", " + we + ")");
        }
    }
}
