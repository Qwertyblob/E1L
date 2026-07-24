package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.dto.OccupiedInterval;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SchedulingLockRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SlotRepository slotRepository;
    @Mock UserRepository userRepository;
    @Mock RateLimiterService rateLimiter;
    @Mock SchedulingLockRepository schedulingLockRepository;
    // Real (pure) guard — its sweep-line logic is exercised directly here rather than stubbed, so
    // the finders feeding it drive accept/reject. SchedulingGuardTest covers the guard in isolation.
    @Spy SchedulingGuard schedulingGuard = new SchedulingGuard();

    @InjectMocks BookingService bookingService;

    @BeforeEach
    void stubUserRowLock() {
        // createBooking now re-reads the user under the scheduling lock via findByIdForUpdate and
        // uses that entity. Default it to the same user for authenticated tests; the deleted-user
        // test overrides this. lenient() because guest-path tests never reach it.
        lenient().when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user()));
    }

    // ─── createBooking ────────────────────────────────────────────────────────────

    // A valid catalog selection (classic / no add-ons) for tests exercising
    // flow beyond the catalog price check. classic = 58, 45 min.
    private static CreateBookingRequest authBooking(Long slotId) {
        return new CreateBookingRequest(slotId, null, null, null, null, null,
                null, null, null, null, null,
                "classic", "junior", "none", "none");
    }

    @Test
    void createBooking_recomputesPriceAndNamesFromCatalog_ignoringClientValues() {
        SlotEntity slot = slot(3, 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            BookingEntity b = inv.getArgument(0);
            b.setId(30L);
            b.setStatus("BOOKED");
            return b;
        });
        // Client sends a forged cheap price + fake names; the server must ignore them.
        CreateBookingRequest request = new CreateBookingRequest(1L, null, null, null, null, null,
                "FREE STUFF", "CEO", "diamonds", "premium", 1,
                "classic", "junior", "tier2", "self-gel");

        BookingResponse response = bookingService.createBooking(request, "alice@example.com");

        assertThat(response.totalPrice()).isEqualTo(91); // classic 58 + tier2 25 + self-gel 8
        assertThat(response.serviceName()).isEqualTo("Classic Manicure");
        assertThat(response.nailArt()).isEqualTo("Tier 2 — Layered");
        assertThat(response.removal()).isEqualTo("Gel / Hard Gel — Done by every1luvs");
    }

    @Test
    void createBooking_unknownService_throws400() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        CreateBookingRequest request = new CreateBookingRequest(1L, null, null, null, null, null,
                null, null, null, null, null, "does-not-exist", "junior", "none", "none");

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(request, "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains("service");
    }

    @Test
    void createBooking_happyPath_incrementsBookedCountAndSaves() {
        SlotEntity slot = slot(3, 1);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            BookingEntity b = inv.getArgument(0);
            b.setId(10L);
            b.setStatus("BOOKED");
            return b;
        });

        BookingResponse response = bookingService.createBooking(authBooking(1L), "alice@example.com");

        assertThat(response.status()).isEqualTo("BOOKED");
        assertThat(slot.getBookedCount()).isEqualTo(2);
        verify(slotRepository).saveAndFlush(slot);
    }

    @Test
    void createBooking_atActiveBookingCap_throws409() {
        SlotEntity slot = slot(3, 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        // Already holding the max upcoming bookings (default cap 5).
        when(bookingRepository.countActiveFutureBookingsForUser(eq(1L), any())).thenReturn(5L);

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("maximum number of upcoming bookings");
        verify(userRepository).findByIdForUpdate(1L); // row lock taken before the count
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBooking_nullRequest_throws400() {
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(null, "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createBooking_nullSlotId_throws400() {
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(new CreateBookingRequest(null), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createBooking_duplicateBooking_throws409() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot(3, 1)));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(true);

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("already have an active booking");
    }

    @Test
    void createBooking_windowFullyBooked_guardRejectsWith409() {
        // Capacity-1 picked slot with one existing appointment already covering the booking's
        // window, so SchedulingGuard rejects. booked_count plays no part in the decision.
        SlotEntity slot = slot(1, 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.findActiveSlotsOverlapping(any(), any())).thenReturn(List.of(slot));
        when(bookingRepository.findActiveOccupiedIntervalsBefore(any()))
                .thenReturn(List.of(new OccupiedInterval(99L, slot.getStartTime(), slot.getEndTime(), 60)));

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("fully booked");
        assertThat(slot.getBookedCount()).isZero(); // guard rejected before any seat was taken
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBooking_bridgingGapBetweenSequentialAppointments_accepted() {
        // Capacity-1 slot 09:00–13:00 with two sequential appointments 09:00–10:00 and 10:00–11:00.
        // A 30-min booking at 09:00 would clash, but the picked slot starts at 09:00 — instead this
        // proves the guard is instant-by-instant: an appointment that abuts (10:00–11:00) doesn't
        // make 09:00–09:30 concurrent beyond capacity when the 09:00 one is absent.
        SlotEntity slot = slot(1, 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(slotRepository.findActiveSlotsOverlapping(any(), any())).thenReturn(List.of(slot));
        // classic = 45 min → picked window is 09:00–09:45; the only existing appointment starts at
        // 09:45 (abutting), so it's never concurrent with the new one.
        when(bookingRepository.findActiveOccupiedIntervalsBefore(any())).thenReturn(List.of(
                new OccupiedInterval(99L, Instant.parse("2099-06-15T09:45:00Z"),
                        Instant.parse("2099-06-15T10:45:00Z"), 60)));
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            BookingEntity b = inv.getArgument(0);
            b.setId(41L);
            b.setStatus("BOOKED");
            return b;
        });

        BookingResponse response = bookingService.createBooking(authBooking(1L), "alice@example.com");

        assertThat(response.status()).isEqualTo("BOOKED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
    }

    @Test
    void createBooking_acquiresSchedulingLockBeforeReadingSlot() {
        SlotEntity slot = slot(3, 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            BookingEntity b = inv.getArgument(0);
            b.setId(42L);
            b.setStatus("BOOKED");
            return b;
        });

        bookingService.createBooking(authBooking(1L), "alice@example.com");

        // The scheduling lock must be taken before any capacity read and before the per-user row
        // lock (fixed order avoids deadlock).
        InOrder order = inOrder(schedulingLockRepository, slotRepository, userRepository);
        order.verify(schedulingLockRepository).acquire();
        order.verify(slotRepository).findById(1L);
        order.verify(userRepository).findByIdForUpdate(1L);
    }

    @Test
    void createBooking_optimisticLockFailure_throws409() {
        SlotEntity slot = slot(3, 1);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.saveAndFlush(slot)).thenThrow(new ObjectOptimisticLockingFailureException(SlotEntity.class, 1L));

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("Please try again");
    }

    @Test
    void createBooking_uniqueConstraintViolation_throws409() {
        // The exists-check passes (TOCTOU), but a concurrent insert trips the partial unique
        // index, so the save throws DataIntegrityViolationException — surface it as 409, not 500.
        SlotEntity slot = slot(3, 1);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(bookingRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("already have an active booking");
    }

    @Test
    void createBooking_userNotFound_throws401() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "ghost@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void createBooking_userDeletedBeforeRowLock_throws401Cleanly() {
        // The user existed at the pre-lock read, but a concurrent account deletion removed the row
        // by the time we lock it. Re-reading under the lock must yield a clean 401 — no seat taken,
        // no booking insert that would trip the user FK as a misleading 409/500.
        SlotEntity slot = slot(3, 0);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(slot.getBookedCount()).isZero();
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void createBooking_slotNotFound_throws404() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(99L)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(99L), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createBooking_guestHappyPath_savesWithoutUserAndUsesCustomerName() {
        SlotEntity slot = slot(3, 0);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(bookingRepository.save(any())).thenAnswer(inv -> {
            BookingEntity b = inv.getArgument(0);
            b.setId(20L);
            b.setStatus("BOOKED");
            return b;
        });

        BookingResponse response = bookingService.createBooking(
                new CreateBookingRequest(1L, "Guest Gina", "gina@example.com", "90001111", "@gina", "ombre please",
                        null, null, null, null, null,
                        "classic", "junior", "none", "none"),
                null);

        assertThat(response.status()).isEqualTo("BOOKED");
        assertThat(response.userId()).isNull();
        assertThat(response.userName()).isEqualTo("Guest Gina");
        assertThat(slot.getBookedCount()).isEqualTo(1);
        verify(bookingRepository).save(any());
    }

    @Test
    void createBooking_guestMissingName_throws400() {
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(
                        new CreateBookingRequest(1L, "  ", "gina@example.com", null, null, null,
                                null, null, null, null, null,
                                "classic", "junior", "none", "none"), null),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createBooking_tooManyAttachments_throws400() {
        BookingAttachment image = new BookingAttachment("inspo.png", "image/png", "aGVsbG8=");
        List<BookingAttachment> six = List.of(image, image, image, image, image, image);
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(
                        new CreateBookingRequest(1L, "Guest Gina", "gina@example.com", null, null, null,
                                null, null, null, null, null,
                                "classic", "junior", "none", "none", six),
                        null),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createBooking_nonImageAttachment_throws400() {
        BookingAttachment pdf = new BookingAttachment("resume.pdf", "application/pdf", "aGVsbG8=");
        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(
                        new CreateBookingRequest(1L, "Guest Gina", "gina@example.com", null, null, null,
                                null, null, null, null, null,
                                "classic", "junior", "none", "none", List.of(pdf)),
                        null),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createBooking_guestRateLimited_throws429() {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many"))
                .when(rateLimiter).check(eq("booking:ip:203.0.113.9"), anyInt(), anyLong(), anyString());

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(
                        new CreateBookingRequest(1L, "Guest Gina", "gina@example.com", null, null, null,
                                null, null, null, null, null,
                                "classic", "junior", "none", "none"),
                        null, "203.0.113.9"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void createBooking_authenticatedRateLimited_throws429() {
        // The authenticated path is now also limited (per-user bucket).
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        // lenient: the authenticated path also calls check() for the IP bucket (different args),
        // which strict stubbing would otherwise flag as a potential stubbing problem.
        lenient().doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many"))
                .when(rateLimiter).check(eq("booking:user:1"), anyInt(), anyLong(), anyString());

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com", "203.0.113.9"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void createBooking_guestDuplicateEmailAndSlot_throws409() {
        SlotEntity slot = slot(3, 1);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        // Case-insensitive match: stored gina@example.com, re-booking as GINA@Example.com.
        when(bookingRepository.existsActiveGuestBookingForSlotAndEmail(1L, "GINA@Example.com"))
                .thenReturn(true);

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(
                        new CreateBookingRequest(1L, "Guest Gina", "GINA@Example.com", null, null, null,
                                null, null, null, null, null,
                                "classic", "junior", "none", "none"), null),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(bookingRepository, never()).save(any());
        assertThat(slot.getBookedCount()).isEqualTo(1); // unchanged, no seat consumed
    }

    // ─── cancelBooking ────────────────────────────────────────────────────────────

    @Test
    void cancelBooking_happyPath_decrementsBookedCountAndTransitions() {
        SlotEntity slot = slot(3, 2);
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBookedForUser(10L, 1L, "CANCELLED")).thenReturn(1);

        BookingResponse response = bookingService.cancelBooking(10L, "alice@example.com");

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
        verify(slotRepository).saveAndFlush(slot);
    }

    @Test
    void cancelBooking_bookingNotFound_throws404() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(99L, 1L)).thenReturn(Optional.empty());

        var ex = catchThrowableOfType(
                () -> bookingService.cancelBooking(99L, "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelBooking_alreadyCancelled_throws409() {
        BookingEntity booking = booking("CANCELLED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(10L, 1L)).thenReturn(Optional.of(booking));

        var ex = catchThrowableOfType(
                () -> bookingService.cancelBooking(10L, "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(bookingRepository, never()).transitionFromBookedForUser(anyLong(), anyLong(), anyString());
    }

    @Test
    void cancelBooking_bookedCountNeverGoesNegative() {
        SlotEntity slot = slot(3, 0); // bookedCount already 0 (defensive floor)
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBookedForUser(10L, 1L, "CANCELLED")).thenReturn(1);

        bookingService.cancelBooking(10L, "alice@example.com");

        assertThat(slot.getBookedCount()).isZero();
    }

    @Test
    void cancelBooking_within72HoursOfSlotStart_rejectedWith400AndBookingKept() {
        SlotEntity slot = slot(3, 2);
        // 24h ahead in the salon's wall-clock-as-UTC convention — inside the 72h notice window.
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).plusHours(24).toInstant(ZoneOffset.UTC));
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));

        var ex = catchThrowableOfType(
                () -> bookingService.cancelBooking(10L, "alice@example.com"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(booking.getStatus()).isEqualTo("BOOKED");
        assertThat(slot.getBookedCount()).isEqualTo(2);
        verify(bookingRepository, never()).transitionFromBookedForUser(anyLong(), anyLong(), anyString());
        verify(slotRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelBooking_moreThan72HoursBeforeSlotStart_succeeds() {
        SlotEntity slot = slot(3, 2);
        // Just past the 72h cutoff in the salon's wall-clock-as-UTC convention.
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).plusHours(73).toInstant(ZoneOffset.UTC));
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBookedForUser(10L, 1L, "CANCELLED")).thenReturn(1);

        BookingResponse response = bookingService.cancelBooking(10L, "alice@example.com");

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
    }

    @Test
    void cancelBooking_decrementsOnlyPickedSlot_noOverlapRelease() {
        // Cancellation is a direct-booking decrement only — it never queries or touches other
        // slots (the retired overlap-lock cache is gone).
        SlotEntity slot = slot(3, 2);
        BookingEntity booking = booking("BOOKED");
        booking.setDurationMin(90);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBookedForUser(10L, 1L, "CANCELLED")).thenReturn(1);

        BookingResponse response = bookingService.cancelBooking(10L, "alice@example.com");

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
        verify(slotRepository).saveAndFlush(slot);
        verify(slotRepository, never()).findActiveSlotsOverlapping(any(), any());
    }

    // ─── getMyBookings ────────────────────────────────────────────────────────────

    @Test
    void getMyBookings_returnsUserBookings() {
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(1L)).thenReturn(List.of(booking));
        when(slotRepository.findAllById(List.of(1L))).thenReturn(List.of(slot(3, 1)));

        List<BookingResponse> result = bookingService.getMyBookings("alice@example.com");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("BOOKED");
    }

    // ─── getAllBookings (paged) ────────────────────────────────────────────────────

    @Test
    void getAllBookings_batchesSlotsAndUsersIntoPage() {
        BookingEntity booking = booking("BOOKED");
        Pageable pageable = PageRequest.of(0, 50);
        when(bookingRepository.findByArchivedAtIsNullOrderByCreatedAtDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(booking), pageable, 1));
        // One findAllById per referenced slot/user set, not one findById per booking.
        when(slotRepository.findAllById(List.of(1L))).thenReturn(List.of(slot(3, 1)));
        when(userRepository.findAllById(List.of(1L))).thenReturn(List.of(user()));

        Page<BookingResponse> result = bookingService.getAllBookings(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).status()).isEqualTo("BOOKED");
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    // ─── adminCancelBooking ───────────────────────────────────────────────────────

    @Test
    void adminCancelBooking_happyPath_cancelsAndDecrementsCount() {
        SlotEntity slot = slot(3, 2);
        BookingEntity booking = booking("BOOKED");
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking));
        when(bookingRepository.transitionFromBooked(10L, "CANCELLED")).thenReturn(1);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingResponse response = bookingService.adminCancelBooking(10L);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
    }

    @Test
    void adminCancelBooking_notFound_throws404() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(99L)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> bookingService.adminCancelBooking(99L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCancelBooking_alreadyCompleted_throws409() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking("COMPLETED")));
        var ex = catchThrowableOfType(() -> bookingService.adminCancelBooking(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(bookingRepository, never()).transitionFromBooked(anyLong(), anyString());
    }

    // ─── adminCompleteBooking ─────────────────────────────────────────────────────

    @Test
    void adminCompleteBooking_afterAppointmentEnded_completes() {
        BookingEntity booking = booking("BOOKED");
        SlotEntity slot = slot(3, 1);
        // Appointment ended an hour ago (salon wall-clock).
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minusHours(2).toInstant(ZoneOffset.UTC));
        slot.setEndTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minusHours(1).toInstant(ZoneOffset.UTC));
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBooked(10L, "COMPLETED")).thenReturn(1);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingResponse response = bookingService.adminCompleteBooking(10L);

        assertThat(response.status()).isEqualTo("COMPLETED");
    }

    @Test
    void adminCompleteBooking_beforeAppointmentEnds_throws400() {
        BookingEntity booking = booking("BOOKED");
        SlotEntity slot = slot(3, 1);
        // Appointment ends two hours from now — can't be completed yet.
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).plusHours(1).toInstant(ZoneOffset.UTC));
        slot.setEndTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).plusHours(2).toInstant(ZoneOffset.UTC));
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));

        var ex = catchThrowableOfType(() -> bookingService.adminCompleteBooking(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains("after it has ended");
        verify(bookingRepository, never()).transitionFromBooked(anyLong(), anyString());
    }

    @Test
    void adminCompleteBooking_notFound_throws404() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(99L)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> bookingService.adminCompleteBooking(99L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCompleteBooking_alreadyCancelled_throws409() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking("CANCELLED")));
        var ex = catchThrowableOfType(() -> bookingService.adminCompleteBooking(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminCompleteBooking_decrementsPickedSlotSeat() {
        // A COMPLETED booking no longer holds a BOOKED seat, so the display counter drops by one in
        // the same transaction as the status flip.
        BookingEntity booking = booking("BOOKED");
        SlotEntity slot = slot(3, 2);
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minusHours(2).toInstant(ZoneOffset.UTC));
        slot.setEndTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minusHours(1).toInstant(ZoneOffset.UTC));
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBooked(10L, "COMPLETED")).thenReturn(1);
        when(slotRepository.saveAndFlush(slot)).thenReturn(slot);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingResponse response = bookingService.adminCompleteBooking(10L);

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
        verify(slotRepository).saveAndFlush(slot);
    }

    @Test
    void adminCompleteBooking_slotOptimisticConflict_throws409() {
        // A racing cancel bumped the slot's version, so the seat decrement's version-checked flush
        // fails. It surfaces as a retryable 409 (rolling the COMPLETED transition back), not a 500.
        BookingEntity booking = booking("BOOKED");
        SlotEntity slot = slot(3, 2);
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minusHours(2).toInstant(ZoneOffset.UTC));
        slot.setEndTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minusHours(1).toInstant(ZoneOffset.UTC));
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.transitionFromBooked(10L, "COMPLETED")).thenReturn(1);
        when(slotRepository.saveAndFlush(slot))
                .thenThrow(new ObjectOptimisticLockingFailureException(SlotEntity.class, 1L));

        var ex = catchThrowableOfType(() -> bookingService.adminCompleteBooking(10L), ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ─── adminConfirmBooking / resendConfirmation ─────────────────────────────────

    @Test
    void adminConfirmBooking_pending_stampsConfirmedAndReportsNewlyConfirmed() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(pendingBooking()));
        when(bookingRepository.markConfirmed(anyLong(), any())).thenReturn(1);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot(3, 1)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingService.ConfirmResult result = bookingService.adminConfirmBooking(10L);

        assertThat(result.newlyConfirmed()).isTrue();
        assertThat(result.booking().confirmedAt()).isNotNull();
    }

    @Test
    void adminConfirmBooking_notFound_throws404() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(99L)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> bookingService.adminConfirmBooking(99L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(bookingRepository, never()).markConfirmed(anyLong(), any());
    }

    @Test
    void adminConfirmBooking_notBooked_throws409() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking("CANCELLED")));
        var ex = catchThrowableOfType(() -> bookingService.adminConfirmBooking(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(bookingRepository, never()).markConfirmed(anyLong(), any());
    }

    @Test
    void adminConfirmBooking_alreadyConfirmed_isIdempotentNoOp() {
        // Claim changes 0 rows because it's already confirmed; reload shows a confirmed BOOKED
        // booking, so it's a legitimate no-op (no email) rather than an error.
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking("BOOKED")));
        when(bookingRepository.markConfirmed(anyLong(), any())).thenReturn(0);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot(3, 1)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingService.ConfirmResult result = bookingService.adminConfirmBooking(10L);

        assertThat(result.newlyConfirmed()).isFalse();
    }

    @Test
    void adminConfirmBooking_cancelWinsRace_throws409() {
        // Claim changes 0 rows because a cancel raced in after the first read; reload shows the
        // booking is no longer BOOKED, so confirm fails rather than falsely "resending".
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L))
                .thenReturn(Optional.of(pendingBooking()), Optional.of(booking("CANCELLED")));
        when(bookingRepository.markConfirmed(anyLong(), any())).thenReturn(0);

        var ex = catchThrowableOfType(() -> bookingService.adminConfirmBooking(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void resendConfirmation_confirmedBooking_returnsResponse() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(booking("BOOKED")));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot(3, 1)));
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingResponse response = bookingService.resendConfirmation(10L);

        assertThat(response.id()).isEqualTo(10L);
    }

    @Test
    void resendConfirmation_pendingBooking_throws409() {
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(pendingBooking()));
        var ex = catchThrowableOfType(() -> bookingService.resendConfirmation(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminCompleteBooking_pendingBooking_throws409() {
        // A never-confirmed booking must not be completable (would fire the review email for a
        // client who was never told their booking went through).
        when(bookingRepository.findByIdAndArchivedAtIsNull(10L)).thenReturn(Optional.of(pendingBooking()));

        var ex = catchThrowableOfType(() -> bookingService.adminCompleteBooking(10L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("Confirm this booking");
        verify(bookingRepository, never()).transitionFromBooked(anyLong(), anyString());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private UserEntity user() {
        UserEntity u = new UserEntity();
        u.setId(1L);
        u.setName("Alice");
        u.setEmail("alice@example.com");
        u.setRole("USER");
        return u;
    }

    // A generous 4-hour future slot. Tests that exercise completion timing override
    // start/end explicitly.
    private SlotEntity slot(int capacity, int bookedCount) {
        SlotEntity s = new SlotEntity();
        s.setId(1L);
        s.setTitle("Morning session");
        s.setStartTime(Instant.parse("2099-06-15T09:00:00Z"));
        s.setEndTime(Instant.parse("2099-06-15T13:00:00Z"));
        s.setCapacity(capacity);
        s.setBookedCount(bookedCount);
        return s;
    }

    // Default helper booking is already admin-confirmed, so complete/cancel tests exercise the
    // post-confirmation lifecycle. Pending (unconfirmed) bookings use pendingBooking().
    private BookingEntity booking(String status) {
        BookingEntity b = new BookingEntity();
        b.setId(10L);
        b.setSlotId(1L);
        b.setUserId(1L);
        b.setStatus(status);
        b.setConfirmedAt(Instant.EPOCH);
        return b;
    }

    private BookingEntity pendingBooking() {
        BookingEntity b = booking("BOOKED");
        b.setConfirmedAt(null);
        return b;
    }
}
