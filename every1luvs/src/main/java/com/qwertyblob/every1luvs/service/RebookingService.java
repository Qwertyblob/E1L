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
 * Sends the rebooking-prompt email once per booking, ~3 weeks after a COMPLETED appointment — a
 * no-show is never marked completed, so it is never nudged. A daily sweep picks up completed,
 * not-yet-nudged bookings whose appointment (slot end_time) is due: at least {@link #REBOOK_DELAY}
 * ago but no older than {@link #REBOOK_DELAY} + {@link #REBOOK_GRACE}. The bounded window stops a
 * first enable from backfilling a year of clients and lets a missed run self-heal within the grace.
 *
 * <p>Stamps {@code rebooking_sent_at} only on a confirmed send (at-least-once, capped at one nudge
 * per booking by the null-guarded {@code markRebookingSent}). Dormant until a booking URL is
 * configured (see {@link BookingMailService#isRebookingEnabled()}). Mirrors {@link ReviewRequestService}.
 */
@Service
public class RebookingService {

    private static final Logger log = LoggerFactory.getLogger(RebookingService.class);

    /** How long after the appointment the rebooking prompt goes out — "3 weeks after". */
    static final Duration REBOOK_DELAY = Duration.ofDays(21);
    /** How long the prompt stays eligible past its due date, bounding backfill and covering misses. */
    static final Duration REBOOK_GRACE = Duration.ofDays(14);

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final BookingMailService bookingMailService;

    public RebookingService(
            BookingRepository bookingRepository,
            SlotRepository slotRepository,
            BookingMailService bookingMailService) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.bookingMailService = bookingMailService;
    }

    /**
     * Runs daily at 10:00 salon time. Once a day is plenty: the eligibility window is
     * {@link #REBOOK_GRACE} wide, so a missed run self-heals on a later day.
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Singapore")
    public void sendDueRebookingPrompts() {
        if (!bookingMailService.isRebookingEnabled()) {
            return; // feature dormant — do no work at all
        }

        Instant now = Instant.now();
        Instant dueBefore = now.minus(REBOOK_DELAY);
        Instant floor = now.minus(REBOOK_DELAY).minus(REBOOK_GRACE);
        List<BookingEntity> due = bookingRepository.findDueForRebooking(dueBefore, floor);
        if (due.isEmpty()) {
            return;
        }

        // One lookup for every slot referenced by the due bookings.
        Map<Long, SlotEntity> slotsById = slotRepository.findAllById(
                        due.stream().map(BookingEntity::getSlotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SlotEntity::getId, Function.identity()));

        int sent = 0;
        for (BookingEntity booking : due) {
            SlotEntity slot = slotsById.get(booking.getSlotId());
            if (slot == null) {
                continue; // slot vanished under us; skip rather than send a detail-less prompt
            }
            // Stamp only on a confirmed send: a transient failure returns false and leaves
            // rebooking_sent_at null so the next sweep retries this booking (at-least-once).
            if (bookingMailService.sendRebookingPrompt(toRebookingResponse(booking, slot))) {
                bookingRepository.markRebookingSent(booking.getId(), now);
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Sent {} rebooking prompt(s) for appointments ~{} ago", sent, REBOOK_DELAY);
        }
    }

    // Customer name/email live on the booking row itself (persisted for registered users too), so no
    // user lookup is needed. Fields the prompt doesn't use are still carried so the mail service
    // sees a complete DTO.
    private BookingResponse toRebookingResponse(BookingEntity booking, SlotEntity slot) {
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
                booking.getConfirmedAt(),
                booking.getCreatedAt()
        );
    }
}
