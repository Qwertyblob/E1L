package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewRequestServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);
    private final BookingMailService mailService = mock(BookingMailService.class);
    private final ReviewRequestService service =
            new ReviewRequestService(bookingRepository, slotRepository, mailService);

    private static SlotEntity slot(long id) {
        SlotEntity slot = new SlotEntity();
        slot.setId(id);
        slot.setTitle("Manicure slot");
        slot.setStartTime(Instant.parse("2026-06-15T14:00:00Z"));
        slot.setEndTime(Instant.parse("2026-06-15T15:00:00Z"));
        return slot;
    }

    private static BookingEntity completedBooking(long id, long slotId) {
        BookingEntity booking = new BookingEntity();
        booking.setId(id);
        booking.setSlotId(slotId);
        booking.setCustomerName("Alice");
        booking.setCustomerEmail("alice@example.com");
        booking.setServiceName("Classic Manicure");
        booking.setStatus("COMPLETED");
        return booking;
    }

    private static BookingResponse response(long id) {
        return new BookingResponse(
                id, 10L, "Manicure slot",
                Instant.parse("2026-06-15T14:00:00Z"), Instant.parse("2026-06-15T15:00:00Z"),
                1L, "Alice", "alice@example.com", "123", "@alice", "notes",
                "Classic Manicure", null, "Tier 1 — Simple", "No removal needed",
                60, "COMPLETED", Instant.EPOCH, Instant.EPOCH);
    }

    // --- Immediate on-complete path ---

    @Test
    void sendReviewRequestNow_confirmedSend_stampsBooking() {
        when(mailService.isReviewRequestEnabled()).thenReturn(true);
        when(mailService.sendReviewRequest(any())).thenReturn(true);

        service.sendReviewRequestNow(response(7L));

        verify(bookingRepository).markReviewSent(eq(7L), any());
    }

    @Test
    void sendReviewRequestNow_disabled_doesNothing() {
        when(mailService.isReviewRequestEnabled()).thenReturn(false);

        service.sendReviewRequestNow(response(7L));

        verify(mailService, never()).sendReviewRequest(any());
        verify(bookingRepository, never()).markReviewSent(any(), any());
    }

    @Test
    void sendReviewRequestNow_sendFails_doesNotStamp() {
        when(mailService.isReviewRequestEnabled()).thenReturn(true);
        when(mailService.sendReviewRequest(any())).thenReturn(false);

        service.sendReviewRequestNow(response(7L));

        verify(bookingRepository, never()).markReviewSent(any(), any());
    }

    // --- Fallback sweep ---

    @Test
    void sendDueReviewRequests_disabled_doesNoWork() {
        when(mailService.isReviewRequestEnabled()).thenReturn(false);

        service.sendDueReviewRequests();

        verify(bookingRepository, never()).findDueForReview(any());
    }

    @Test
    void sendDueReviewRequests_confirmedSend_stampsBooking() {
        when(mailService.isReviewRequestEnabled()).thenReturn(true);
        when(bookingRepository.findDueForReview(any())).thenReturn(List.of(completedBooking(7L, 10L)));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of(slot(10L)));
        when(mailService.sendReviewRequest(any())).thenReturn(true);

        service.sendDueReviewRequests();

        verify(bookingRepository).markReviewSent(eq(7L), any());
    }

    @Test
    void sendDueReviewRequests_sendFails_doesNotStampSoItRetriesNextSweep() {
        when(mailService.isReviewRequestEnabled()).thenReturn(true);
        when(bookingRepository.findDueForReview(any())).thenReturn(List.of(completedBooking(7L, 10L)));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of(slot(10L)));
        when(mailService.sendReviewRequest(any())).thenReturn(false);

        service.sendDueReviewRequests();

        verify(bookingRepository, never()).markReviewSent(any(), any());
    }
}
