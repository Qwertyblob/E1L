package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.BatchCreateSlotsRequest;
import com.qwertyblob.every1luvs.dto.CreateSlotRequest;
import com.qwertyblob.every1luvs.dto.PageResponse;
import com.qwertyblob.every1luvs.dto.SlotResponse;
import com.qwertyblob.every1luvs.dto.UpdateSlotRequest;
import com.qwertyblob.every1luvs.service.SlotService;
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
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlotControllerTest {

    @Mock SlotService slotService;

    @InjectMocks SlotController slotController;

    private static SlotResponse slot(Long id) {
        return new SlotResponse(id, "Slot " + id, "desc", Instant.EPOCH, Instant.EPOCH, 3, 0, true, false, Instant.EPOCH);
    }

    @Test
    void listAllSlots_delegatesToServiceAndWrapsPage() {
        List<SlotResponse> slots = List.of(slot(1L), slot(2L));
        Pageable pageable = PageRequest.of(0, 50);
        Page<SlotResponse> page = new PageImpl<>(slots, pageable, 2);
        when(slotService.listAllSlots(pageable)).thenReturn(page);

        PageResponse<SlotResponse> result = slotController.listAllSlots(pageable);

        assertThat(result.content()).isEqualTo(slots);
        assertThat(result.totalElements()).isEqualTo(2);
    }

    @Test
    void listAvailableSlots_passesQuoteToService() {
        List<SlotResponse> expected = List.of(slot(1L));
        when(slotService.listAvailableSlots("classic", "tier2", "none")).thenReturn(expected);

        assertThat(slotController.listAvailableSlots("classic", "tier2", "none")).isEqualTo(expected);
    }

    @Test
    void getSlot_delegatesToService() {
        SlotResponse expected = slot(7L);
        when(slotService.getSlot(7L)).thenReturn(expected);

        assertThat(slotController.getSlot(7L)).isEqualTo(expected);
    }

    @Test
    void createSlot_returns201WithBody() {
        CreateSlotRequest request = new CreateSlotRequest("Slot", "desc", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", 3);
        SlotResponse created = slot(1L);
        when(slotService.createSlot(request)).thenReturn(created);

        ResponseEntity<SlotResponse> response = slotController.createSlot(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void createSlots_batch_returns201WithList() {
        BatchCreateSlotsRequest request = new BatchCreateSlotsRequest(List.of(
                new CreateSlotRequest("A", "d", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", 3)
        ));
        List<SlotResponse> created = List.of(slot(1L), slot(2L));
        when(slotService.createSlots(request)).thenReturn(created);

        ResponseEntity<List<SlotResponse>> response = slotController.createSlots(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(created);
    }

    @Test
    void updateSlot_delegatesToService() {
        UpdateSlotRequest request = new UpdateSlotRequest("New", "desc", "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", 5);
        SlotResponse updated = slot(9L);
        when(slotService.updateSlot(9L, request)).thenReturn(updated);

        assertThat(slotController.updateSlot(9L, request)).isEqualTo(updated);
    }

    @Test
    void deleteSlot_returns204AndCallsService() {
        ResponseEntity<Void> response = slotController.deleteSlot(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(response.getBody()).isNull();
        verify(slotService).deleteSlot(5L);
    }
}
