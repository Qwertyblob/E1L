package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReminderServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final SlotRepository slotRepository = mock(SlotRepository.class);
    private final BookingMailService mailService = mock(BookingMailService.class);
    private final ReminderService service =
            new ReminderService(bookingRepository, slotRepository, mailService);

    private static SlotEntity slot(long id) {
        SlotEntity slot = new SlotEntity();
        slot.setId(id);
        slot.setTitle("Manicure slot");
        slot.setStartTime(Instant.parse("2026-06-15T14:00:00Z"));
        slot.setEndTime(Instant.parse("2026-06-15T15:00:00Z"));
        return slot;
    }

    private static BookingEntity booking(long id, long slotId) {
        BookingEntity booking = new BookingEntity();
        booking.setId(id);
        booking.setSlotId(slotId);
        booking.setCustomerName("Alice");
        booking.setCustomerEmail("alice@example.com");
        booking.setServiceName("Classic Manicure");
        booking.setStatus("BOOKED");
        return booking;
    }

    @Test
    void sendDueReminders_confirmedSend_stampsBooking() {
        BookingEntity due = booking(7L, 10L);
        when(bookingRepository.findDueForReminder(any(), any())).thenReturn(List.of(due));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of(slot(10L)));
        when(mailService.sendBookingReminder(any())).thenReturn(true);

        service.sendDueReminders();

        ArgumentCaptor<BookingResponse> captor = ArgumentCaptor.forClass(BookingResponse.class);
        verify(mailService).sendBookingReminder(captor.capture());
        assertThat(captor.getValue().customerEmail()).isEqualTo("alice@example.com");
        assertThat(captor.getValue().slotStartTime()).isEqualTo(Instant.parse("2026-06-15T14:00:00Z"));
        // Stamped only after the confirmed send.
        verify(bookingRepository).markReminderSent(eq(7L), any());
    }

    @Test
    void sendDueReminders_sendFails_doesNotStampSoItRetriesNextSweep() {
        BookingEntity due = booking(7L, 10L);
        when(bookingRepository.findDueForReminder(any(), any())).thenReturn(List.of(due));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of(slot(10L)));
        when(mailService.sendBookingReminder(any())).thenReturn(false); // transient failure

        service.sendDueReminders();

        verify(bookingRepository, never()).markReminderSent(any(), any());
    }

    @Test
    void sendDueReminders_nothingDue_sendsNoMail() {
        when(bookingRepository.findDueForReminder(any(), any())).thenReturn(List.of());

        service.sendDueReminders();

        verifyNoInteractions(mailService);
    }

    @Test
    void sendDueReminders_skipsBookingWhoseSlotIsMissing() {
        BookingEntity due = booking(7L, 10L);
        when(bookingRepository.findDueForReminder(any(), any())).thenReturn(List.of(due));
        when(slotRepository.findAllById(anyIterable())).thenReturn(List.of()); // slot gone

        service.sendDueReminders();

        verify(mailService, never()).sendBookingReminder(any());
        verify(bookingRepository, never()).markReminderSent(any(), any());
    }
}
