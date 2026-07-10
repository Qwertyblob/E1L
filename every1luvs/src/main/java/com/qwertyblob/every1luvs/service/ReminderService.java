package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends the pre-appointment reminder email once per booking, at most ~2 days before the
 * appointment. A daily sweep picks up active BOOKED bookings whose slot starts within the next
 * {@link #REMINDER_LEAD} and that haven't been reminded yet, stamps {@code reminder_sent_at} so a
 * later sweep never double-sends, and hands the mail off to {@link BookingMailService} (async,
 * best-effort). Mirrors {@link ArchivalService}'s scheduled-sweep style and Singapore zone.
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    /** How far ahead of the appointment the reminder goes out — "at most 2 days before". */
    static final Duration REMINDER_LEAD = Duration.ofDays(2);

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final BookingMailService bookingMailService;

    public ReminderService(
            BookingRepository bookingRepository,
            SlotRepository slotRepository,
            BookingMailService bookingMailService) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.bookingMailService = bookingMailService;
    }

    /** Runs daily at 09:00 salon time, so reminders land at a reasonable hour. */
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Singapore")
    @Transactional
    public void sendDueReminders() {
        Instant now = Instant.now();
        Instant windowEnd = now.plus(REMINDER_LEAD);
        List<BookingEntity> due = bookingRepository.findDueForReminder(now, windowEnd);
        if (due.isEmpty()) {
            return;
        }

        // One lookup for every slot referenced by the due bookings; the reminder needs the slot's
        // start time (and title) to render the appointment details.
        Map<Long, SlotEntity> slotsById = slotRepository.findAllById(
                        due.stream().map(BookingEntity::getSlotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SlotEntity::getId, Function.identity()));

        int sent = 0;
        for (BookingEntity booking : due) {
            SlotEntity slot = slotsById.get(booking.getSlotId());
            if (slot == null) {
                continue; // slot vanished under us; skip rather than send a detail-less reminder
            }
            bookingMailService.sendBookingReminder(toReminderResponse(booking, slot));
            // Stamp inside the transaction so a re-run (or the next daily sweep) can't re-send,
            // even though the mail itself is async/best-effort.
            booking.setReminderSentAt(now);
            sent++;
        }
        if (sent > 0) {
            log.info("Queued {} booking reminder(s) for appointments within {}", sent, REMINDER_LEAD);
        }
    }

    // Customer name/email live on the booking row itself (persisted for registered users too), so
    // no user lookup is needed. Add-on/price fields the reminder doesn't use are still carried so
    // the mail service sees a complete DTO.
    private BookingResponse toReminderResponse(BookingEntity booking, SlotEntity slot) {
        return new BookingResponse(
                booking.getId(),
                slot.getId(),
                slot.getTitle(),
                slot.getStartTime(),
                slot.getEndTime(),
                booking.getUserId(),
                booking.getCustomerName(),
                booking.getCustomerEmail(),
                booking.getPhone(),
                booking.getInstagram(),
                booking.getNotes(),
                booking.getServiceName(),
                booking.getTechnician(),
                booking.getNailArt(),
                booking.getRemoval(),
                booking.getTotalPrice(),
                booking.getStatus(),
                booking.getCreatedAt()
        );
    }
}
