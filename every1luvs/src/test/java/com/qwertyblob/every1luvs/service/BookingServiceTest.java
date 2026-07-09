package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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

    @InjectMocks BookingService bookingService;

    // ─── createBooking ────────────────────────────────────────────────────────────

    // A valid catalog selection (classic / no add-ons) for tests exercising
    // flow beyond the catalog price check. classic = 58.
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
    void createBooking_slotFull_throws409() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot(1, 1))); // full
        when(bookingRepository.existsActiveBookingForUserAndSlot(1L, 1L)).thenReturn(false);

        var ex = catchThrowableOfType(
                () -> bookingService.createBooking(authBooking(1L), "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("fully booked");
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
                .when(rateLimiter).check(eq("booking:203.0.113.9"), anyInt(), anyLong(), anyString());

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
    void cancelBooking_happyPath_decrementsBookedCountAndSaves() {
        SlotEntity slot = slot(3, 2);
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findActiveBookingByIdAndUserId(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.save(booking)).thenReturn(booking);

        BookingResponse response = bookingService.cancelBooking(10L, "alice@example.com");

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
        // saveAndFlush forces the version-checked UPDATE so a concurrent cancel surfaces as 409.
        verify(slotRepository).saveAndFlush(slot);
    }

    @Test
    void cancelBooking_bookingNotFound_throws404() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findActiveBookingByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        var ex = catchThrowableOfType(
                () -> bookingService.cancelBooking(99L, "alice@example.com"),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void cancelBooking_bookedCountNeverGoesNegative() {
        SlotEntity slot = slot(3, 0); // bookedCount already 0 (defensive floor)
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findActiveBookingByIdAndUserId(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.save(booking)).thenReturn(booking);

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
        when(bookingRepository.findActiveBookingByIdAndUserId(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));

        var ex = catchThrowableOfType(
                () -> bookingService.cancelBooking(10L, "alice@example.com"),
                ResponseStatusException.class);

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(booking.getStatus()).isEqualTo("BOOKED");
        assertThat(slot.getBookedCount()).isEqualTo(2);
        verify(bookingRepository, never()).save(any());
        verify(slotRepository, never()).saveAndFlush(any());
    }

    @Test
    void cancelBooking_moreThan72HoursBeforeSlotStart_succeeds() {
        SlotEntity slot = slot(3, 2);
        // Just past the 72h cutoff in the salon's wall-clock-as-UTC convention.
        slot.setStartTime(LocalDateTime.now(BookingWindow.BUSINESS_ZONE).plusHours(73).toInstant(ZoneOffset.UTC));
        BookingEntity booking = booking("BOOKED");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user()));
        when(bookingRepository.findActiveBookingByIdAndUserId(10L, 1L)).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.save(booking)).thenReturn(booking);

        BookingResponse response = bookingService.cancelBooking(10L, "alice@example.com");

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
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
        when(bookingRepository.findByIdAndStatusAndArchivedAtIsNull(10L, "BOOKED")).thenReturn(Optional.of(booking));
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.save(booking)).thenReturn(booking);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user()));

        BookingResponse response = bookingService.adminCancelBooking(10L);

        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(slot.getBookedCount()).isEqualTo(1);
    }

    @Test
    void adminCancelBooking_notFound_throws404() {
        when(bookingRepository.findByIdAndStatusAndArchivedAtIsNull(99L, "BOOKED")).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> bookingService.adminCancelBooking(99L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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

    private SlotEntity slot(int capacity, int bookedCount) {
        SlotEntity s = new SlotEntity();
        s.setId(1L);
        s.setTitle("Morning session");
        s.setStartTime(Instant.parse("2099-06-15T09:00:00Z"));
        s.setEndTime(Instant.parse("2099-06-15T10:00:00Z"));
        s.setCapacity(capacity);
        s.setBookedCount(bookedCount);
        return s;
    }

    private BookingEntity booking(String status) {
        BookingEntity b = new BookingEntity();
        b.setId(10L);
        b.setSlotId(1L);
        b.setUserId(1L);
        b.setStatus(status);
        return b;
    }
}
