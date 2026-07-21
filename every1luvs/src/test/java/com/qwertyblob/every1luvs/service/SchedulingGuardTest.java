package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.service.SchedulingGuard.Appointment;
import com.qwertyblob.every1luvs.service.SchedulingGuard.CapacityTile;
import com.qwertyblob.every1luvs.service.SchedulingGuard.Violation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulingGuardTest {

    private final SchedulingGuard guard = new SchedulingGuard();

    // Wall-clock-as-UTC instants on a fixed day; only relative ordering matters here.
    private static Instant at(int hour, int minute) {
        return Instant.parse(String.format("2099-06-15T%02d:%02d:00Z", hour, minute));
    }

    private static Appointment appt(int fromH, int fromM, int toH, int toM, Long id) {
        return new Appointment(at(fromH, fromM), at(toH, toM), id);
    }

    private static CapacityTile tile(int fromH, int fromM, int toH, int toM, int capacity, Long id) {
        return new CapacityTile(at(fromH, fromM), at(toH, toM), capacity, id);
    }

    // ── canAddAppointment ──────────────────────────────────────────────────────────────────

    @Test
    void bridgingAppointmentOverSequentialOnesIsAccepted() {
        // capacity 2 all day; A 14:00-15:00 and B 15:00-16:00 never overlap each other (peak 1).
        // A new 14:30-15:30 appointment peaks at 2 (with A, then with B) — never 3 — so it fits.
        List<CapacityTile> tiles = List.of(tile(14, 0, 16, 0, 2, 1L));
        List<Appointment> existing = List.of(appt(14, 0, 15, 0, 10L), appt(15, 0, 16, 0, 11L));

        assertThat(guard.canAddAppointment(at(14, 30), at(15, 30), existing, tiles)).isTrue();
    }

    @Test
    void thirdGenuinelyConcurrentAppointmentIsRejectedAtCapacityTwo() {
        List<CapacityTile> tiles = List.of(tile(14, 0, 16, 0, 2, 1L));
        List<Appointment> existing = List.of(appt(14, 0, 15, 30, 10L), appt(14, 0, 15, 30, 11L));

        assertThat(guard.canAddAppointment(at(14, 0), at(15, 0), existing, tiles)).isFalse();
    }

    @Test
    void appointmentAndTileBeginningBeforeTheWindowAreCounted() {
        // Existing appointment and the capacity tile both START before ws (14:30). Without clipping
        // them into the window they'd contribute no in-window start event and be missed.
        List<CapacityTile> tiles = List.of(tile(13, 0, 16, 0, 1, 1L)); // capacity 1, starts before ws
        List<Appointment> existing = List.of(appt(13, 0, 15, 0, 10L));  // starts before ws, active at ws

        // ws=14:30 is already at capacity 1 (existing covers it), so a new appt cannot be added.
        assertThat(guard.canAddAppointment(at(14, 30), at(15, 30), existing, tiles)).isFalse();
    }

    @Test
    void conflictOnlyInTheFinalSegmentEndingAtWeIsDetected() {
        // Capacity 1 window 14:00-15:00. Existing appointment occupies only the last segment 14:45-15:00.
        List<CapacityTile> tiles = List.of(tile(14, 0, 15, 0, 1, 1L));
        List<Appointment> existing = List.of(appt(14, 45, 15, 0, 10L));

        assertThat(guard.canAddAppointment(at(14, 0), at(15, 0), existing, tiles)).isFalse();
        // A new appointment that ends before the conflicting tail fits.
        assertThat(guard.canAddAppointment(at(14, 0), at(14, 45), existing, tiles)).isTrue();
    }

    @Test
    void abuttingAppointmentsAreNotConcurrent() {
        // Capacity 1; existing 14:00-15:00; a new 15:00-16:00 abuts it and must be allowed.
        List<CapacityTile> tiles = List.of(tile(14, 0, 16, 0, 1, 1L));
        List<Appointment> existing = List.of(appt(14, 0, 15, 0, 10L));

        assertThat(guard.canAddAppointment(at(15, 0), at(16, 0), existing, tiles)).isTrue();
    }

    @Test
    void noCoveringTileMeansUnconstrained() {
        // No tiles at all: any number of appointments is allowed (capacity unconstrained).
        List<Appointment> existing = List.of(appt(14, 0, 15, 30, 10L), appt(14, 0, 15, 30, 11L));

        assertThat(guard.canAddAppointment(at(14, 0), at(15, 0), existing, List.of())).isTrue();
    }

    @Test
    void overlappingTilesSharingTheMinimum_oneEnding_retainsRemainingMinimum() {
        // T1 capacity 2 over 14:00-16:00; T2 capacity 1 over 14:00-15:00. Effective capacity is
        // min = 1 during 14:00-15:00, then rises to 2 during 15:00-16:00 once T2 ends. A single new
        // appointment 14:00-15:00 (peak concurrency 1) fits at cap 1; a second concurrent one there
        // would not, but one spanning into 15:00-16:00 with no rival does.
        List<CapacityTile> tiles = List.of(tile(14, 0, 16, 0, 2, 1L), tile(14, 0, 15, 0, 1, 2L));

        // 14:00-15:00 alone: cap 1, adding 1 -> ok.
        assertThat(guard.canAddAppointment(at(14, 0), at(15, 0), List.of(), tiles)).isTrue();
        // With one already there over 14:00-15:00, a second concurrent one exceeds the min cap 1.
        assertThat(guard.canAddAppointment(at(14, 0), at(15, 0), List.of(appt(14, 0, 15, 0, 10L)), tiles))
                .isFalse();
        // But over 15:00-16:00 the min cap is 2 (only T1 covers), so a second there is fine.
        assertThat(guard.canAddAppointment(at(15, 0), at(16, 0), List.of(appt(15, 0, 16, 0, 10L)), tiles))
                .isTrue();
    }

    @Test
    void invalidWindowIsRejected() {
        assertThatThrownBy(() -> guard.canAddAppointment(at(15, 0), at(15, 0), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.canAddAppointment(at(16, 0), at(15, 0), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── capacityChangeIsSafe ───────────────────────────────────────────────────────────────

    @Test
    void reducingCapacityBelowExistingConcurrencyIsUnsafe() {
        // Two appointments run concurrently 14:00-15:00. Proposing a capacity-1 tile there is unsafe.
        List<Appointment> existing = List.of(appt(14, 0, 15, 0, 10L), appt(14, 0, 15, 0, 11L));
        List<CapacityTile> proposed = List.of(tile(14, 0, 15, 0, 1, 1L));

        assertThat(guard.capacityChangeIsSafe(existing, proposed, at(14, 0), at(15, 0))).isFalse();
        // Capacity 2 covers the two concurrent appointments.
        assertThat(guard.capacityChangeIsSafe(existing, List.of(tile(14, 0, 15, 0, 2, 1L)),
                at(14, 0), at(15, 0))).isTrue();
    }

    // ── findViolations ─────────────────────────────────────────────────────────────────────

    @Test
    void findViolationsReportsOverCapacityIntervalsWithIds() {
        // Three concurrent appointments 14:00-15:00 against a capacity-2 tile → one violating segment.
        List<CapacityTile> tiles = List.of(tile(14, 0, 15, 0, 2, 1L));
        List<Appointment> appts = List.of(
                appt(14, 0, 15, 0, 10L), appt(14, 0, 15, 0, 11L), appt(14, 0, 15, 0, 12L));

        List<Violation> violations = guard.findViolations(appts, tiles);

        assertThat(violations).hasSize(1);
        Violation v = violations.get(0);
        assertThat(v.concurrency()).isEqualTo(3);
        assertThat(v.capacity()).isEqualTo(2);
        assertThat(v.bookingIds()).containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(v.slotIds()).containsExactly(1L);
        assertThat(v.from()).isEqualTo(at(14, 0));
        assertThat(v.to()).isEqualTo(at(15, 0));
    }

    @Test
    void findViolationsIsEmptyWhenWithinCapacity() {
        List<CapacityTile> tiles = List.of(tile(14, 0, 16, 0, 2, 1L));
        List<Appointment> appts = List.of(appt(14, 0, 15, 0, 10L), appt(15, 0, 16, 0, 11L));

        assertThat(guard.findViolations(appts, tiles)).isEmpty();
    }
}
