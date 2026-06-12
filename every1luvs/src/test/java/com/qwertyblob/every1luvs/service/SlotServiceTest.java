package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BatchCreateSlotsRequest;
import com.qwertyblob.every1luvs.dto.CreateSlotRequest;
import com.qwertyblob.every1luvs.dto.SlotResponse;
import com.qwertyblob.every1luvs.dto.UpdateSlotRequest;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlotServiceTest {

    @Mock SlotRepository slotRepository;
    @Mock BookingRepository bookingRepository;

    @InjectMocks SlotService slotService;

    private static final String FUTURE_START = "2099-06-15T09:00";
    private static final String FUTURE_END   = "2099-06-15T10:00";

    // ─── createSlot ──────────────────────────────────────────────────────────────

    @Test
    void createSlot_happyPath_savesAndReturns() {
        when(slotRepository.save(any())).thenAnswer(inv -> {
            SlotEntity s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        SlotResponse response = slotService.createSlot(
                new CreateSlotRequest("Morning session", null, FUTURE_START, FUTURE_END, 5));

        assertThat(response.title()).isEqualTo("Morning session");
        assertThat(response.capacity()).isEqualTo(5);
        assertThat(response.bookedCount()).isZero();
        assertThat(response.available()).isTrue();
        verify(slotRepository).save(any());
    }

    @Test
    void createSlot_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> slotService.createSlot(null), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_blankTitle_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlot(new CreateSlotRequest("  ", null, FUTURE_START, FUTURE_END, 1)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_titleTooLong_throws400() {
        String longTitle = "A".repeat(256);
        var ex = catchThrowableOfType(
                () -> slotService.createSlot(new CreateSlotRequest(longTitle, null, FUTURE_START, FUTURE_END, 1)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_endBeforeStart_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlot(new CreateSlotRequest("Session", null, FUTURE_END, FUTURE_START, 1)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_capacityZero_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlot(new CreateSlotRequest("Session", null, FUTURE_START, FUTURE_END, 0)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_invalidTimeFormat_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlot(new CreateSlotRequest("Session", null, "not-a-date", FUTURE_END, 1)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_endEqualToStart_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlot(new CreateSlotRequest("Session", null, FUTURE_START, FUTURE_START, 1)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlot_nullCapacity_defaultsToOne() {
        when(slotRepository.save(any())).thenAnswer(inv -> {
            SlotEntity s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        SlotResponse response = slotService.createSlot(
                new CreateSlotRequest("Session", null, FUTURE_START, FUTURE_END, null));

        assertThat(response.capacity()).isEqualTo(1);
    }

    @Test
    void createSlot_titleWhitespaceTrimmed() {
        when(slotRepository.save(any())).thenAnswer(inv -> {
            SlotEntity s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        slotService.createSlot(new CreateSlotRequest("  Morning session  ", null, FUTURE_START, FUTURE_END, 1));

        verify(slotRepository).save(argThat(s -> "Morning session".equals(s.getTitle())));
    }

    // ─── createSlots (batch) ─────────────────────────────────────────────────────

    @Test
    void createSlots_nullRequest_throws400() {
        var ex = catchThrowableOfType(() -> slotService.createSlots(null), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlots_nullSlotsList_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlots(new BatchCreateSlotsRequest(null)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlots_emptyList_throws400() {
        var ex = catchThrowableOfType(
                () -> slotService.createSlots(new BatchCreateSlotsRequest(List.of())),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlots_singleSlot_returnsListOfOne() {
        when(slotRepository.save(any())).thenAnswer(inv -> {
            SlotEntity s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        List<SlotResponse> result = slotService.createSlots(new BatchCreateSlotsRequest(List.of(
                new CreateSlotRequest("Morning session", null, FUTURE_START, FUTURE_END, 5)
        )));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).title()).isEqualTo("Morning session");
        verify(slotRepository, times(1)).save(any());
    }

    @Test
    void createSlots_multipleSlots_savesAllInOrder() {
        final long[] idSeq = { 1L };
        when(slotRepository.save(any())).thenAnswer(inv -> {
            SlotEntity s = inv.getArgument(0);
            s.setId(idSeq[0]++);
            return s;
        });

        List<SlotResponse> result = slotService.createSlots(new BatchCreateSlotsRequest(List.of(
                new CreateSlotRequest("Slot A", null, "2099-06-15T09:00", "2099-06-15T10:00", 5),
                new CreateSlotRequest("Slot B", null, "2099-06-15T10:00", "2099-06-15T11:00", 3),
                new CreateSlotRequest("Slot C", null, "2099-06-15T11:00", "2099-06-15T12:00", 10)
        )));

        assertThat(result).hasSize(3);
        assertThat(result.stream().map(SlotResponse::title))
                .containsExactly("Slot A", "Slot B", "Slot C");
        verify(slotRepository, times(3)).save(any());
    }

    @Test
    void createSlots_invalidSlotInBatch_throws400() {
        // Invalid slot placed first so it throws before any save is attempted
        var ex = catchThrowableOfType(
                () -> slotService.createSlots(new BatchCreateSlotsRequest(List.of(
                        new CreateSlotRequest("  ", null, FUTURE_START, FUTURE_END, 1),
                        new CreateSlotRequest("Valid", null, FUTURE_START, FUTURE_END, 1)
                ))),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createSlots_capacityNullInBatch_defaultsToOne() {
        when(slotRepository.save(any())).thenAnswer(inv -> {
            SlotEntity s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });

        List<SlotResponse> result = slotService.createSlots(new BatchCreateSlotsRequest(List.of(
                new CreateSlotRequest("Session", null, FUTURE_START, FUTURE_END, null)
        )));

        assertThat(result.get(0).capacity()).isEqualTo(1);
    }

    // ─── updateSlot ──────────────────────────────────────────────────────────────

    @Test
    void updateSlot_happyPath_updatesTitle() {
        SlotEntity slot = savedSlot(5, 2);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(slotRepository.save(any())).thenReturn(slot);

        SlotResponse response = slotService.updateSlot(1L,
                new UpdateSlotRequest("New title", null, null, null, null));

        assertThat(response.title()).isEqualTo("New title");
    }

    @Test
    void updateSlot_changeTimeWithBookings_throws409() {
        SlotEntity slot = savedSlot(5, 2);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));

        var ex = catchThrowableOfType(
                () -> slotService.updateSlot(1L,
                        new UpdateSlotRequest(null, null, "2099-06-16T09:00", "2099-06-16T10:00", null)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("time");
    }

    @Test
    void updateSlot_reduceCapacityBelowBookedCount_throws409() {
        SlotEntity slot = savedSlot(5, 3);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));

        var ex = catchThrowableOfType(
                () -> slotService.updateSlot(1L, new UpdateSlotRequest(null, null, null, null, 2)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateSlot_notFound_throws404() {
        when(slotRepository.findById(99L)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(
                () -> slotService.updateSlot(99L, new UpdateSlotRequest("T", null, null, null, null)),
                ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ─── deleteSlot ──────────────────────────────────────────────────────────────

    @Test
    void deleteSlot_noActiveBookings_clearsCancelledAndDeletes() {
        SlotEntity slot = savedSlot(1, 0);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsNonCancelledBookingBySlotId(1L)).thenReturn(false);

        slotService.deleteSlot(1L);

        // Cancelled bookings that still reference the slot are removed first so they don't
        // orphan and 404 the booking-list endpoints, then the slot itself is deleted.
        verify(bookingRepository).deleteBySlotId(1L);
        verify(slotRepository).delete(slot);
    }

    @Test
    void deleteSlot_notFound_throws404() {
        when(slotRepository.findById(99L)).thenReturn(Optional.empty());
        var ex = catchThrowableOfType(() -> slotService.deleteSlot(99L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteSlot_withActiveOrCompletedBookings_throws409AndDoesNotDelete() {
        SlotEntity slot = savedSlot(1, 1);
        when(slotRepository.findById(1L)).thenReturn(Optional.of(slot));
        when(bookingRepository.existsNonCancelledBookingBySlotId(1L)).thenReturn(true);

        var ex = catchThrowableOfType(() -> slotService.deleteSlot(1L), ResponseStatusException.class);
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getReason()).contains("bookings");
        verify(slotRepository, never()).delete(any());
        verify(bookingRepository, never()).deleteBySlotId(any());
    }

    // ─── list ────────────────────────────────────────────────────────────────────

    @Test
    void listAllSlots_returnsMappedPage() {
        Pageable pageable = PageRequest.of(0, 50);
        when(slotRepository.findByArchivedAtIsNullOrderByStartTimeAsc(pageable))
                .thenReturn(new PageImpl<>(List.of(savedSlot(3, 1)), pageable, 1));

        Page<SlotResponse> result = slotService.listAllSlots(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).available()).isTrue();
        assertThat(result.getContent().get(0).archived()).isFalse();
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void listArchivedSlots_returnsArchivedPageWithFlagSet() {
        Pageable pageable = PageRequest.of(0, 50);
        SlotEntity archived = savedSlot(3, 1);
        archived.setArchivedAt(Instant.now());
        when(slotRepository.findByArchivedAtIsNotNullOrderByStartTimeDesc(pageable))
                .thenReturn(new PageImpl<>(List.of(archived), pageable, 1));

        Page<SlotResponse> result = slotService.listArchivedSlots(pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).archived()).isTrue();
    }

    @Test
    void listAvailableSlots_returnsOnlyAvailable() {
        // Stubbing the exact cutoff also asserts the service filters by the bookable window:
        // if it passed a different Instant the stub wouldn't match and the list would be empty.
        when(slotRepository.findAvailableSlots(BookingWindow.earliestBookableStartUtc()))
                .thenReturn(List.of(savedSlot(2, 0)));

        List<SlotResponse> result = slotService.listAvailableSlots();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).available()).isTrue();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    private SlotEntity savedSlot(int capacity, int bookedCount) {
        SlotEntity s = new SlotEntity();
        s.setId(1L);
        s.setTitle("Morning session");
        s.setStartTime(Instant.parse("2099-06-15T09:00:00Z"));
        s.setEndTime(Instant.parse("2099-06-15T10:00:00Z"));
        s.setCapacity(capacity);
        s.setBookedCount(bookedCount);
        return s;
    }
}
