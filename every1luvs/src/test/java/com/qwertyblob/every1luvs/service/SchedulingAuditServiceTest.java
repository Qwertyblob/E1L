package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.OccupiedInterval;
import com.qwertyblob.every1luvs.dto.SchedulingConflictResponse;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulingAuditServiceTest {

    @Mock BookingRepository bookingRepository;
    @Mock SlotRepository slotRepository;
    @Spy SchedulingGuard schedulingGuard = new SchedulingGuard();

    @InjectMocks SchedulingAuditService auditService;

    private static final Instant START = Instant.parse("2099-06-15T14:00:00Z");
    private static final Instant END = Instant.parse("2099-06-15T15:00:00Z");

    @Test
    void findConflicts_overCapacitySchedule_reportsPreciselyAndCancelsNothing() {
        // A capacity-1 slot with two overlapping BOOKED appointments — concurrency 2 > capacity 1.
        when(bookingRepository.findAllActiveOccupiedIntervals()).thenReturn(List.of(
                new OccupiedInterval(1L, START, END, 60),
                new OccupiedInterval(2L, START, END, 60)));
        when(slotRepository.findActiveSlots()).thenReturn(List.of(slot(10L, 1)));

        List<SchedulingConflictResponse> conflicts = auditService.findConflicts();

        assertThat(conflicts).hasSize(1);
        SchedulingConflictResponse conflict = conflicts.get(0);
        assertThat(conflict.observedConcurrency()).isEqualTo(2);
        assertThat(conflict.effectiveCapacity()).isEqualTo(1);
        assertThat(conflict.bookingIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(conflict.slotIds()).containsExactly(10L);
        assertThat(conflict.from()).isEqualTo(START);
        assertThat(conflict.to()).isEqualTo(END);

        // Read-only audit: it must never cancel/complete a booking or write a slot.
        verify(bookingRepository, never()).transitionFromBooked(anyLong(), anyString());
        verify(slotRepository, never()).save(any());
        verify(slotRepository, never()).saveAndFlush(any());
    }

    @Test
    void findConflicts_withinCapacity_returnsEmpty() {
        when(bookingRepository.findAllActiveOccupiedIntervals())
                .thenReturn(List.of(new OccupiedInterval(1L, START, END, 60)));
        when(slotRepository.findActiveSlots()).thenReturn(List.of(slot(10L, 2)));

        assertThat(auditService.findConflicts()).isEmpty();
    }

    private SlotEntity slot(long id, int capacity) {
        SlotEntity s = new SlotEntity();
        s.setId(id);
        s.setStartTime(START);
        s.setEndTime(END);
        s.setCapacity(capacity);
        s.setBookedCount(0);
        return s;
    }
}
