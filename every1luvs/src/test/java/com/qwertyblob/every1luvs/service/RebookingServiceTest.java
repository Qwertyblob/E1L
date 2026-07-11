package com.qwertyblob.every1luvs.service;

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

class RebookingServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);
    private final BookingMailService mailService = mock(BookingMailService.class);
    private final RebookingService service =
            new RebookingService(bookingRepository, slotRepository, mailService);

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

    @Test
    void sendDueRebookingPrompts_disabled_doesNoWork() {
        when(mailService.isRebookingEnabled()).thenReturn(false);

        service.sendDueRebookingPrompts();

        verify(bookingRepository, never()).findDueForRebooking(any(), any());
    }

    @Test
    void sendDueRebookingPrompts_confirmedSend_stampsBooking() {
        when(mailService.isRebookingEnabled()).thenReturn(true);
        when(bookingRepository.findDueForRebooking(any(), any())).thenReturn(List.of(completedBooking(7L, 10L)));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of(slot(10L)));
        when(mailService.sendRebookingPrompt(any())).thenReturn(true);

        service.sendDueRebookingPrompts();

        verify(bookingRepository).markRebookingSent(eq(7L), any());
    }

    @Test
    void sendDueRebookingPrompts_sendFails_doesNotStampSoItRetriesNextSweep() {
        when(mailService.isRebookingEnabled()).thenReturn(true);
        when(bookingRepository.findDueForRebooking(any(), any())).thenReturn(List.of(completedBooking(7L, 10L)));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of(slot(10L)));
        when(mailService.sendRebookingPrompt(any())).thenReturn(false);

        service.sendDueRebookingPrompts();

        verify(bookingRepository, never()).markRebookingSent(any(), any());
    }
}
