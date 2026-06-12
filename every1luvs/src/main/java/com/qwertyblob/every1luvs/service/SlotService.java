package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BatchCreateSlotsRequest;
import com.qwertyblob.every1luvs.dto.CreateSlotRequest;
import com.qwertyblob.every1luvs.dto.SlotResponse;
import com.qwertyblob.every1luvs.dto.UpdateSlotRequest;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class SlotService {

    private static final int MAX_TITLE_LENGTH = 255;

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;

    public SlotService(SlotRepository slotRepository, BookingRepository bookingRepository) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
    }

    @Transactional
    public SlotResponse createSlot(CreateSlotRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot details are required.");
        }
        return saveSlot(request);
    }

    @Transactional
    public List<SlotResponse> createSlots(BatchCreateSlotsRequest request) {
        if (request == null || request.slots() == null || request.slots().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one slot is required.");
        }
        return request.slots().stream().map(this::saveSlot).toList();
    }

    private SlotResponse saveSlot(CreateSlotRequest request) {
        String title = normalizeText(request.title());
        Instant startTime = parseTime(request.startTime(), "Start time");
        Instant endTime = parseTime(request.endTime(), "End time");
        int capacity = request.capacity() != null ? request.capacity() : 1;

        validateSlotFields(title, startTime, endTime, capacity);

        SlotEntity slot = new SlotEntity();
        slot.setTitle(title);
        slot.setDescription(normalizeText(request.description()));
        slot.setStartTime(startTime);
        slot.setEndTime(endTime);
        slot.setCapacity(capacity);
        slot.setBookedCount(0);

        return SlotResponse.from(slotRepository.save(slot));
    }

    @Transactional
    public SlotResponse updateSlot(Long id, UpdateSlotRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Update details are required.");
        }

        SlotEntity slot = loadOrThrow(id);

        String title = request.title() != null ? normalizeText(request.title()) : slot.getTitle();
        String description = request.description() != null ? normalizeText(request.description()) : slot.getDescription();
        Instant startTime = request.startTime() != null ? parseTime(request.startTime(), "Start time") : slot.getStartTime();
        Instant endTime = request.endTime() != null ? parseTime(request.endTime(), "End time") : slot.getEndTime();
        int capacity = request.capacity() != null ? request.capacity() : slot.getCapacity();

        validateSlotFields(title, startTime, endTime, capacity);

        if (capacity < slot.getBookedCount()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Capacity cannot be reduced below the current number of active bookings (" + slot.getBookedCount() + ").");
        }

        boolean timeChanged = !startTime.equals(slot.getStartTime()) || !endTime.equals(slot.getEndTime());
        if (timeChanged && slot.getBookedCount() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot change the time of a slot that has bookings. Cancel the bookings first.");
        }

        slot.setTitle(title);
        slot.setDescription(description);
        slot.setStartTime(startTime);
        slot.setEndTime(endTime);
        slot.setCapacity(capacity);

        return SlotResponse.from(slotRepository.save(slot));
    }

    @Transactional
    public void deleteSlot(Long id) {
        SlotEntity slot = loadOrThrow(id);
        // Bookings reference the slot by id with no DB cascade. Block deletion while any
        // non-cancelled (active or completed) booking still points at it, so we never orphan
        // a live/historical booking or break the booking-list endpoints (which 404 on a
        // missing slot). Cancelled bookings are terminal, so clear them out with the slot.
        if (bookingRepository.existsNonCancelledBookingBySlotId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete a slot that has active or completed bookings. Cancel them first.");
        }
        bookingRepository.deleteBySlotId(id);
        slotRepository.delete(slot);
    }

    @Transactional(readOnly = true)
    public Page<SlotResponse> listAllSlots(Pageable pageable) {
        return slotRepository.findByArchivedAtIsNullOrderByStartTimeAsc(pageable).map(SlotResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<SlotResponse> listArchivedSlots(Pageable pageable) {
        return slotRepository.findByArchivedAtIsNotNullOrderByStartTimeDesc(pageable).map(SlotResponse::from);
    }

    public List<SlotResponse> listAvailableSlots() {
        // Hide slots that the booking path would reject (same-day / past), so the
        // availability list never offers a slot that can't actually be booked.
        return slotRepository.findAvailableSlots(BookingWindow.earliestBookableStartUtc())
                .stream()
                .map(SlotResponse::from)
                .toList();
    }

    public SlotResponse getSlot(Long id) {
        return SlotResponse.from(loadOrThrow(id));
    }

    SlotEntity loadOrThrow(Long id) {
        return slotRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found."));
    }

    private void validateSlotFields(String title, Instant startTime, Instant endTime, int capacity) {
        if (title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title is required.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Title must not exceed " + MAX_TITLE_LENGTH + " characters.");
        }
        if (!endTime.isAfter(startTime)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End time must be after start time.");
        }
        if (capacity < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Capacity must be at least 1.");
        }
    }

    private Instant parseTime(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is required.");
        }
        try {
            return LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fieldName + " must be a valid date-time (e.g. 2025-06-15T14:30).");
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }
}
