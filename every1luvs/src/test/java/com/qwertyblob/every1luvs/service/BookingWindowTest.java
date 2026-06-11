package com.qwertyblob.every1luvs.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class BookingWindowTest {

    @Test
    void earliestBookableStart_isTomorrowMidnightInTheSalonZone() {
        // "One day in advance" is the salon's (Asia/Singapore) calendar day, rendered in the
        // wall-clock-as-UTC convention slots are stored in. Computing "tomorrow" in UTC instead
        // would let customers book same-day slots between 00:00 and 08:00 SGT.
        Instant expected = LocalDate.now(BookingWindow.BUSINESS_ZONE)
                .plusDays(1)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);

        assertThat(BookingWindow.earliestBookableStartUtc()).isEqualTo(expected);
    }

    @Test
    void earliestBookableStart_isStrictlyAfterTheCurrentSalonWallClock() {
        // The boundary must always sit in the salon's future, so no already-started slot
        // can ever be bookable regardless of when this runs.
        Instant nowAsStoredWallClock = Instant.now().plusSeconds(
                BookingWindow.BUSINESS_ZONE.getRules().getOffset(Instant.now()).getTotalSeconds());
        assertThat(BookingWindow.earliestBookableStartUtc()).isAfter(nowAsStoredWallClock);
    }
}
