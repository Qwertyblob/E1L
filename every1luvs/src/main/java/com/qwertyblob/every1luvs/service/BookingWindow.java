package com.qwertyblob.every1luvs.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Single source of truth for the earliest start time a slot may be booked at.
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

    private BookingWindow() {
    }

    public static Instant earliestBookableStartUtc() {
        // Tomorrow's date in the salon's zone, expressed as the stored wall-clock-as-UTC convention.
        return LocalDate.now(BUSINESS_ZONE).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
