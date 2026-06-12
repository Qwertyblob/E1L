package com.qwertyblob.every1luvs.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Single source of truth for the booking and cancellation time windows.
 *
 * <p>Slot times are stored as the admin's entered wall-clock labelled UTC, and "one day
 * in advance" must mean the salon's calendar day — so "tomorrow" is computed in
 * {@link #BUSINESS_ZONE}, not UTC. (With UTC, customers between midnight and 08:00
 * Singapore time could still book a same-day slot, because the UTC date lags SGT by
 * 8 hours.) Used by both the booking path (enforcement) and the availability listing
 * (so unbookable slots are never shown), keeping the two from drifting.
 */
public final class BookingWindow {

    /** The salon operates in Singapore; its calendar day defines "same-day". */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Singapore");

    /** Customers must cancel at least this long before the slot starts. */
    public static final Duration CANCELLATION_NOTICE = Duration.ofHours(72);

    private BookingWindow() {
    }

    public static Instant earliestBookableStartUtc() {
        // Tomorrow's date in the salon's zone, expressed as the stored wall-clock-as-UTC convention.
        return LocalDate.now(BUSINESS_ZONE).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /**
     * Earliest slot start a customer may still cancel: the salon's current wall-clock
     * plus {@link #CANCELLATION_NOTICE}, expressed in the stored wall-clock-as-UTC
     * convention so it compares directly against slot start times.
     */
    public static Instant earliestCancellableStartUtc() {
        return LocalDateTime.now(BUSINESS_ZONE).plus(CANCELLATION_NOTICE).toInstant(ZoneOffset.UTC);
    }
}
