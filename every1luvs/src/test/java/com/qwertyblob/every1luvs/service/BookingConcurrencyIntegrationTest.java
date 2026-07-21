package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-PostgreSQL concurrency proof for the Workstream B booking-integrity fixes. Mockito verifies
 * calls, not transactional behaviour; H2 doesn't model {@code SELECT ... FOR UPDATE} or optimistic
 * version conflicts the way Postgres does. These tests run the actual service against a throwaway
 * Postgres 17 container (schema built by the real Flyway migrations) and hammer it from many threads
 * released simultaneously.
 *
 * <p>Proves:
 * <ol>
 *   <li>Concurrent authenticated bookings cannot exceed the active-booking cap (pessimistic user-row
 *       lock closes the count-then-insert race).</li>
 *   <li>A concurrent cancel and complete on one booking yield exactly one winner (atomic guarded
 *       {@code UPDATE ... WHERE status='BOOKED'}), the loser 409s.</li>
 *   <li>Two cancels on a shared slot never leave inconsistent state: the seat count always equals
 *       capacity minus the number of cancels that succeeded, and a cancel that loses the slot's
 *       optimistic-version race rolls its status transition back to BOOKED (transition + seat
 *       release are one transaction).</li>
 * </ol>
 */
@SpringBootTest(properties = {
        // TokenService enforces a >=32-char secret at startup; the real Flyway migrations own the
        // schema, so ddl-auto stays at its 'validate' default (which also checks the migrations).
        "app.auth.token-secret=integration-test-secret-that-is-32-chars",
        "app.booking.max-active-per-user=5"
})
@Testcontainers
class BookingConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final int SUCCESS = 200;
    private static final int CAP = 5;

    @Autowired private BookingService bookingService;
    @Autowired private SlotService slotService;
    @Autowired private UserRepository userRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clean() {
        bookingRepository.deleteAll();
        slotRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void concurrentBookings_neverExceedTheActiveCap() throws Exception {
        UserEntity user = createUser("cap-user@test.local");
        // One distinct future slot per concurrent request (the active-user/slot unique index would
        // otherwise reject duplicates on the same slot before the cap is even reached).
        int attempts = CAP + 3;
        List<Long> slotIds = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            slotIds.add(createFutureSlot(i + 1, 1).getId());
        }

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (Long slotId : slotIds) {
            tasks.add(() -> statusOf(() -> bookingService.createBooking(bookingRequest(slotId), user.getEmail(), null)));
        }
        List<Integer> results = runSimultaneously(tasks);

        long booked = results.stream().filter(s -> s == SUCCESS).count();
        long conflicts = results.stream().filter(s -> s == 409).count();
        assertThat(booked).as("exactly the cap may be booked").isEqualTo(CAP);
        assertThat(conflicts).as("the rest are rejected with 409").isEqualTo(attempts - CAP);
        assertThat(bookingRepository.countActiveFutureBookingsForUser(
                user.getId(), BookingWindow.currentBusinessWallClockUtc())).isEqualTo(CAP);
    }

    @Test
    void concurrentCancelAndComplete_produceExactlyOneWinner() throws Exception {
        UserEntity user = createUser("race-user@test.local");
        SlotEntity slot = createPastSlot(1); // completion requires the slot to have ended
        BookingEntity booking = seedBooking(user, slot);

        List<Integer> results = runSimultaneously(List.of(
                () -> statusOf(() -> bookingService.adminCancelBooking(booking.getId())),
                () -> statusOf(() -> bookingService.adminCompleteBooking(booking.getId()))
        ));

        assertThat(results.stream().filter(s -> s == SUCCESS).count())
                .as("exactly one of cancel/complete wins").isEqualTo(1);
        assertThat(results.stream().filter(s -> s == 409).count())
                .as("the loser gets 409, not 500").isEqualTo(1);
        String finalStatus = bookingRepository.findById(booking.getId()).orElseThrow().getStatus();
        assertThat(finalStatus).as("winner's terminal status is committed").isIn("CANCELLED", "COMPLETED");
    }

    @Test
    void concurrentCancelsOnSharedSlot_keepSeatCountConsistent() throws Exception {
        // Two different users (the active user/slot unique index forbids one user booking a slot
        // twice) each hold a seat on the same capacity-2 slot.
        SlotEntity slot = createFutureSlot(2, 2);
        slot.setBookedCount(2);
        slotRepository.save(slot);
        BookingEntity b1 = seedBooking(createUser("share-1@test.local"), slot);
        BookingEntity b2 = seedBooking(createUser("share-2@test.local"), slot);

        List<Integer> results = runSimultaneously(List.of(
                () -> statusOf(() -> bookingService.adminCancelBooking(b1.getId())),
                () -> statusOf(() -> bookingService.adminCancelBooking(b2.getId()))
        ));

        long succeeded = results.stream().filter(s -> s == SUCCESS).count();
        // Every non-success is a retryable 409 (the optimistic slot-version conflict rolling the
        // transition back), never a 500.
        assertThat(results.stream().allMatch(s -> s == SUCCESS || s == 409)).isTrue();

        SlotEntity after = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(after.getBookedCount())
                .as("seat count = capacity minus the cancels that committed, never negative")
                .isEqualTo((int) (2 - succeeded));
        assertThat(after.getBookedCount()).isGreaterThanOrEqualTo(0);
        // Atomicity: a booking is CANCELLED iff its seat release committed. A cancel that lost the
        // version race is fully rolled back — its booking is still BOOKED (no orphaned half-state).
        long cancelled = List.of(b1, b2).stream()
                .map(b -> bookingRepository.findById(b.getId()).orElseThrow().getStatus())
                .filter("CANCELLED"::equals)
                .count();
        assertThat(cancelled).as("cancelled bookings match committed seat releases").isEqualTo(succeeded);
    }

    @Test
    void concurrentConfirmsAcrossOverlappingSlots_capViaMinInstantCapacity() throws Exception {
        // Two DIFFERENT slot rows covering the same window, each capacity 2 → capacity(t)=min=2.
        // The old per-slot bookedCount cache would let each row fill to 2 (4 total); the sweep-line
        // guard counts appointments across both rows against the min, so only 2 succeed overall.
        Instant start = BookingWindow.earliestBookableStartUtc().plus(Duration.ofDays(2));
        Instant end = start.plus(Duration.ofHours(2));
        SlotEntity slotA = saveSlot(start, end, 2);
        SlotEntity slotB = saveSlot(start, end, 2);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            // Distinct users (the user/slot unique index forbids duplicates), alternating rows.
            Long slotId = (i % 2 == 0) ? slotA.getId() : slotB.getId();
            UserEntity user = createUser("overlap-" + i + "@test.local");
            tasks.add(() -> statusOf(() -> bookingService.createBooking(bookingRequest(slotId), user.getEmail(), null)));
        }
        List<Integer> results = runSimultaneously(tasks);

        assertThat(results.stream().filter(s -> s == SUCCESS).count())
                .as("min capacity over the shared window is 2, counted across both rows").isEqualTo(2);
        assertThat(results.stream().filter(s -> s == 409).count()).isEqualTo(4);
    }

    @Test
    void concurrentDeleteAndConfirm_neverStrandsABookingOnADeletedSlot() throws Exception {
        // The V1 FK is ON DELETE CASCADE, so an unlocked check-then-delete could cascade-delete a
        // just-inserted booking. Under the shared scheduling lock the two serialize: delete-first =>
        // the confirmation 404s; confirm-first => delete sees the booking and 409s. Either way no
        // booking is ever left pointing at a missing slot, and neither raises a 500.
        Instant start = BookingWindow.earliestBookableStartUtc().plus(Duration.ofDays(2));
        SlotEntity slot = saveSlot(start, start.plus(Duration.ofHours(2)), 1);
        UserEntity user = createUser("delete-race@test.local");

        List<Integer> results = runSimultaneously(List.of(
                () -> statusOf(() -> bookingService.createBooking(bookingRequest(slot.getId()), user.getEmail(), null)),
                () -> statusOf(() -> { slotService.deleteSlot(slot.getId()); return null; })
        ));

        assertThat(results.stream().allMatch(s -> s == SUCCESS || s == 409 || s == 404))
                .as("every outcome is a clean success/conflict/not-found, never a 500").isTrue();
        // No committed booking may reference a slot that no longer exists.
        long strandedBookings = bookingRepository.findAll().stream()
                .filter(b -> slotRepository.findById(b.getSlotId()).isEmpty())
                .count();
        assertThat(strandedBookings).as("no booking left on a cascade-deleted slot").isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Runs each task on its own thread, released at the same instant, and returns their results. */
    private List<Integer> runSimultaneously(List<Callable<Integer>> tasks) throws Exception {
        int n = tasks.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all threads reached the start line").isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** SUCCESS for a normal return; the HTTP status for a ResponseStatusException; rethrows anything else. */
    private int statusOf(Callable<?> call) {
        try {
            call.call();
            return SUCCESS;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        } catch (Exception e) {
            throw new AssertionError("unexpected non-ResponseStatusException from the service", e);
        }
    }

    private static final AtomicInteger SEQ = new AtomicInteger();

    private UserEntity createUser(String email) {
        UserEntity user = new UserEntity();
        user.setName("Test " + SEQ.incrementAndGet());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("Password123"));
        user.setRole("USER");
        user.setVerifiedAccount(true);
        return userRepository.save(user);
    }

    private SlotEntity createFutureSlot(int dayOffset, int capacity) {
        Instant start = BookingWindow.earliestBookableStartUtc().plus(Duration.ofDays(dayOffset));
        return saveSlot(start, start.plus(Duration.ofHours(2)), capacity);
    }

    private SlotEntity createPastSlot(int capacity) {
        Instant end = BookingWindow.currentBusinessWallClockUtc().minus(Duration.ofHours(1));
        return saveSlot(end.minus(Duration.ofHours(2)), end, capacity);
    }

    private SlotEntity saveSlot(Instant start, Instant end, int capacity) {
        SlotEntity slot = new SlotEntity();
        slot.setTitle("Test slot");
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setCapacity(capacity);
        slot.setBookedCount(0);
        return slotRepository.save(slot);
    }

    private BookingEntity seedBooking(UserEntity user, SlotEntity slot) {
        BookingEntity booking = new BookingEntity();
        booking.setSlotId(slot.getId());
        booking.setUserId(user.getId());
        booking.setCustomerName(user.getName());
        booking.setCustomerEmail(user.getEmail());
        booking.setServiceName("Classic Manicure");
        booking.setTotalPrice(58);
        booking.setStatus("BOOKED");
        return bookingRepository.save(booking);
    }

    /** Authenticated booking request priced against the real catalog (classic = 45 min, fits a 2h slot). */
    private CreateBookingRequest bookingRequest(Long slotId) {
        return new CreateBookingRequest(
                slotId, null, null, null, null, null, null, null, null, null, null,
                "classic", null, "none", "none");
    }
}
