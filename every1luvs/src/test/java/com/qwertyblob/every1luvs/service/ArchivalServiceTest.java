package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchivalServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SlotRepository slotRepository;

    @InjectMocks ArchivalService archivalService;

    @Test
    void archiveExpiredRecords_archivesBookingsAfterOneYearAndSlotsAfterThreeMonths() {
        when(bookingRepository.archiveBookingsForSlotsEndedBefore(any(), any())).thenReturn(2);
        when(slotRepository.archiveSlotsEndedBefore(any(), any())).thenReturn(3);

        // Sandwich the call between two recomputations of the expected cutoffs: the
        // service's cutoffs are derived from "now", so they must land in [before, after].
        Instant bookingBefore = wallClockUtcMinus(1, 0);
        Instant slotBefore = wallClockUtcMinus(0, 3);
        archivalService.archiveExpiredRecords();
        Instant bookingAfter = wallClockUtcMinus(1, 0);
        Instant slotAfter = wallClockUtcMinus(0, 3);

        ArgumentCaptor<Instant> bookingCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> bookingNow = ArgumentCaptor.forClass(Instant.class);
        verify(bookingRepository).archiveBookingsForSlotsEndedBefore(bookingCutoff.capture(), bookingNow.capture());
        assertThat(bookingCutoff.getValue()).isBetween(bookingBefore, bookingAfter);

        ArgumentCaptor<Instant> slotCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> slotNow = ArgumentCaptor.forClass(Instant.class);
        verify(slotRepository).archiveSlotsEndedBefore(slotCutoff.capture(), slotNow.capture());
        assertThat(slotCutoff.getValue()).isBetween(slotBefore, slotAfter);

        // Both row types are stamped with the same archival instant.
        assertThat(bookingNow.getValue()).isEqualTo(slotNow.getValue());
    }

    @Test
    void cutoffs_useSalonWallClockAsUtcConvention() {
        // Slot end times are wall-clock-as-UTC (see BookingWindow), so the cutoffs must be
        // derived from the Singapore wall clock — real UTC would archive 8 hours late.
        Instant before = wallClockUtcMinus(1, 0);
        Instant cutoff = ArchivalService.bookingCutoffUtc();
        Instant after = wallClockUtcMinus(1, 0);
        assertThat(cutoff).isBetween(before, after);

        before = wallClockUtcMinus(0, 3);
        Instant slotCutoff = ArchivalService.slotCutoffUtc();
        after = wallClockUtcMinus(0, 3);
        assertThat(slotCutoff).isBetween(before, after);
    }

    private static Instant wallClockUtcMinus(int years, int months) {
        return LocalDateTime.now(BookingWindow.BUSINESS_ZONE)
                .minusYears(years)
                .minusMonths(months)
                .toInstant(ZoneOffset.UTC);
    }
}
