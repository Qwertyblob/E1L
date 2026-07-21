package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.BatchCreateSlotsRequest;
import com.qwertyblob.every1luvs.dto.CreateSlotRequest;
import com.qwertyblob.every1luvs.dto.PageResponse;
import com.qwertyblob.every1luvs.dto.SlotResponse;
import com.qwertyblob.every1luvs.dto.UpdateSlotRequest;
import com.qwertyblob.every1luvs.service.SlotService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class SlotController {

    static final int MAX_ARCHIVED_PAGE_SIZE = 100;

    private final SlotService slotService;

    public SlotController(SlotService slotService) {
        this.slotService = slotService;
    }

    @GetMapping("/slots")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<SlotResponse> listAllSlots(@PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(slotService.listAllSlots(pageable));
    }

    // Archived slots are a separate, on-demand listing so the regular slot list
    // (fetched on every Schedule view) never carries the growing archive. The page
    // size is capped because the archive grows without bound; the client pages
    // through it rather than pulling everything at once.
    @GetMapping("/admin/slots/archived")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<SlotResponse> listArchivedSlots(@PageableDefault(size = MAX_ARCHIVED_PAGE_SIZE) Pageable pageable) {
        if (pageable.getPageSize() > MAX_ARCHIVED_PAGE_SIZE) {
            pageable = PageRequest.of(pageable.getPageNumber(), MAX_ARCHIVED_PAGE_SIZE, pageable.getSort());
        }
        return PageResponse.of(slotService.listArchivedSlots(pageable));
    }

    // Quote-aware availability: serviceId is required, add-ons default to "none". The service
    // recomputes the appointment duration from the catalog and asks SchedulingGuard which starts
    // could still admit it; an unknown/partial quote is a 400.
    @GetMapping("/slots/available")
    public List<SlotResponse> listAvailableSlots(
            @RequestParam(required = false) String serviceId,
            @RequestParam(required = false) String nailArtId,
            @RequestParam(required = false) String removalId) {
        return slotService.listAvailableSlots(serviceId, nailArtId, removalId);
    }

    @GetMapping("/slots/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SlotResponse getSlot(@PathVariable Long id) {
        return slotService.getSlot(id);
    }

    @PostMapping("/admin/slots")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SlotResponse> createSlot(@RequestBody CreateSlotRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(slotService.createSlot(request));
    }

    @PostMapping("/admin/slots/batch")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<SlotResponse>> createSlots(@RequestBody BatchCreateSlotsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(slotService.createSlots(request));
    }

    @PutMapping("/admin/slots/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SlotResponse updateSlot(@PathVariable Long id, @RequestBody UpdateSlotRequest request) {
        return slotService.updateSlot(id, request);
    }

    @DeleteMapping("/admin/slots/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteSlot(@PathVariable Long id) {
        slotService.deleteSlot(id);
        return ResponseEntity.noContent().build();
    }
}
