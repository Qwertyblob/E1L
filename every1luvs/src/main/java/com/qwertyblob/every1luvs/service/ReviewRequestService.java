package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sends the post-appointment review-request email once per booking, and only for bookings the
 * admin has marked COMPLETED — a no-show is never marked completed, so it is never asked to review.
 *
 * <p>Two paths, both stamping {@code review_sent_at} only on a confirmed send (at-least-once,
 * capped at one request per booking by the null-guarded {@code markReviewSent}):
 * <ul>
 *   <li><b>Immediate</b> — {@link #sendReviewRequestNow} fires from {@code adminCompleteBooking}
 *       the moment a booking is completed (async, so the admin's click stays fast).</li>
 *   <li><b>Fallback sweep</b> — {@link #sendDueReviewRequests} retries any that failed the
 *       immediate send. It only looks back {@link #RECENCY_WINDOW}, so enabling the feature (by
 *       configuring the review URL) doesn't blast months-old completed clients.</li>
 * </ul>
 * The whole feature is dormant until a review URL is configured (see
 * {@link BookingMailService#isReviewRequestEnabled()}). Mirrors {@link ReminderService}.
 */
@Service
public class ReviewRequestService {

    private static final Logger log = LoggerFactory.getLogger(ReviewRequestService.class);

    /** How far back the fallback sweep looks, bounding backfill when the feature is first enabled. */
    static final Duration RECENCY_WINDOW = Duration.ofDays(14);

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final BookingMailService bookingMailService;

    public ReviewRequestService(
            BookingRepository bookingRepository,
            SlotRepository slotRepository,
            BookingMailService bookingMailService) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.bookingMailService = bookingMailService;
    }

    /**
     * Immediate on-complete send. Async so the admin's "Mark completed" click isn't blocked on
     * SMTP; on a confirmed send it stamps the booking so the fallback sweep won't re-send, and on
     * failure it leaves the booking for the sweep to retry.
     */
    @Async
    public void sendReviewRequestNow(BookingResponse booking) {
        if (booking == null || !bookingMailService.isReviewRequestEnabled()) {
            return; // dormant until configured — the booking stays unstamped for a later sweep
        }
        if (bookingMailService.sendReviewRequest(booking)) {
            bookingRepository.markReviewSent(booking.id(), Instant.now());
        }
    }

    /** Fallback sweep, four times a day during salon hours (mirrors {@link ReminderService}). */
    @Scheduled(cron = "0 30 9,13,17,21 * * *", zone = "Asia/Singapore")
    public void sendDueReviewRequests() {
        if (!bookingMailService.isReviewRequestEnabled()) {
            return; // feature dormant — do no work at all
        }

        Instant now = Instant.now();
        List<BookingEntity> due = bookingRepository.findDueForReview(now.minus(RECENCY_WINDOW));
        if (due.isEmpty()) {
            return;
        }

        // One lookup for every slot referenced by the due bookings; the request needs the slot's
        // details to render the appointment it is about.
        Map<Long, SlotEntity> slotsById = slotRepository.findAllById(
                        due.stream().map(BookingEntity::getSlotId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SlotEntity::getId, Function.identity()));

        int sent = 0;
        for (BookingEntity booking : due) {
            SlotEntity slot = slotsById.get(booking.getSlotId());
            if (slot == null) {
                continue; // slot vanished under us; skip rather than send a detail-less request
            }
            // Stamp only on a confirmed send: a transient failure returns false and leaves
            // review_sent_at null so the next sweep retries this booking (at-least-once).
            if (bookingMailService.sendReviewRequest(toReviewResponse(booking, slot))) {
                bookingRepository.markReviewSent(booking.getId(), now);
                sent++;
            }
        }
        if (sent > 0) {
            log.info("Sent {} review request(s) for recently completed appointments", sent);
        }
    }

    // Customer name/email live on the booking row itself (persisted for registered users too), so
    // no user lookup is needed. Fields the request doesn't use are still carried so the mail service
    // sees a complete DTO.
    private BookingResponse toReviewResponse(BookingEntity booking, SlotEntity slot) {
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
