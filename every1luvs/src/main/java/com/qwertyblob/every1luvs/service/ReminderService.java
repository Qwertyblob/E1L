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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends the pre-appointment reminder email once per booking, at most ~2 days before the
 * appointment. Sweeps run several times a day (during salon hours) and pick up active BOOKED
 * bookings whose slot starts within the next {@link #REMINDER_LEAD} and that haven't been
 * reminded yet. Each reminder is sent synchronously; only a confirmed send stamps
 * {@code reminder_sent_at}, so a transient mail failure is retried on the next sweep
 * (at-least-once), while the null-guarded stamp keeps it to at most one reminder per booking.
 * Running often also means a missed sweep (deploy, crash) self-heals within hours rather than a
 * day. Mirrors {@link ArchivalService}'s scheduled-sweep style and Singapore zone.
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

    /**
     * Sweeps four times a day during salon hours (09:00, 13:00, 17:00, 21:00 SGT). Multiple daily
     * sweeps give each booking several chances to be reminded, so a single missed run doesn't drop
     * a reminder, while keeping every send inside daytime hours. The sweep is deliberately not
     * wrapped in one big transaction: each send is followed by its own short {@code markReminderSent}
     * write, so a crash mid-loop keeps the already-sent bookings stamped and holds no DB transaction
     * open across SMTP calls.
     */
    @Scheduled(cron = "0 0 9,13,17,21 * * *", zone = "Asia/Singapore")
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
            // Stamp only on a confirmed send: a transient failure returns false and leaves
            // reminder_sent_at null so the next sweep retries this booking (at-least-once).
            if (bookingMailService.sendBookingReminder(toReminderResponse(booking, slot))) {
                bookingRepository.markReminderSent(booking.getId(), now);
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Sent {} booking reminder(s) for appointments within {}", sent, REMINDER_LEAD);
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
