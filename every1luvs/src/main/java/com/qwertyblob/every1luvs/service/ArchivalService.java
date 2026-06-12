package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;

/**
 * Nightly soft-archival of old records: slots 3 months after they end, bookings 1 year
 * after their appointment (the slot's end time). Archived rows get archived_at stamped
 * and drop out of every listing, but stay in their tables — bookings outlive their slot's
 * archival by 9 months and must still resolve it, so rows are never moved or deleted.
 */
@Service
public class ArchivalService {

    private static final Logger log = LoggerFactory.getLogger(ArchivalService.class);

    static final Period BOOKING_RETENTION = Period.ofYears(1);
    static final Period SLOT_RETENTION = Period.ofMonths(3);

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;

    public ArchivalService(BookingRepository bookingRepository, SlotRepository slotRepository) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
    }

    /** Runs nightly at 04:00 salon time, well outside booking hours. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Singapore")
    @Transactional
    public void archiveExpiredRecords() {
        Instant now = Instant.now();
        int bookings = bookingRepository.archiveBookingsForSlotsEndedBefore(bookingCutoffUtc(), now);
        int slots = slotRepository.archiveSlotsEndedBefore(slotCutoffUtc(), now);
        if (bookings > 0 || slots > 0) {
            log.info("Archived {} booking(s) older than {} and {} slot(s) older than {}",
                    bookings, BOOKING_RETENTION, slots, SLOT_RETENTION);
        }
    }

    /**
     * Cutoffs compare against slot end times, which are stored as the salon's wall clock
     * labelled UTC (see {@link BookingWindow}) — so "now minus retention" is computed from
     * the salon's wall clock and expressed in the same convention, not from real UTC.
     */
    static Instant bookingCutoffUtc() {
        return cutoff(BOOKING_RETENTION);
    }

    static Instant slotCutoffUtc() {
        return cutoff(SLOT_RETENTION);
    }

    private static Instant cutoff(Period retention) {
        return LocalDateTime.now(BookingWindow.BUSINESS_ZONE).minus(retention).toInstant(ZoneOffset.UTC);
    }
}
