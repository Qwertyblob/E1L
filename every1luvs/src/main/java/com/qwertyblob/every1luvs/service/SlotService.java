package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BatchCreateSlotsRequest;
import com.qwertyblob.every1luvs.dto.CreateSlotRequest;
import com.qwertyblob.every1luvs.dto.SlotResponse;
import com.qwertyblob.every1luvs.dto.UpdateSlotRequest;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SchedulingLockRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class SlotService {

    private static final int MAX_TITLE_LENGTH = 255;
    // How far ahead the public availability endpoint looks. Bounds per-request work; slots beyond
    // this simply aren't offered yet (the salon books weeks, not months, in advance).
    private static final Duration AVAILABILITY_HORIZON = Duration.ofDays(120);

    private final SlotRepository slotRepository;
    private final BookingRepository bookingRepository;
    private final SchedulingGuard schedulingGuard;
    private final SchedulingLockRepository schedulingLockRepository;

    public SlotService(SlotRepository slotRepository, BookingRepository bookingRepository,
                       SchedulingGuard schedulingGuard, SchedulingLockRepository schedulingLockRepository) {
        this.slotRepository = slotRepository;
        this.bookingRepository = bookingRepository;
        this.schedulingGuard = schedulingGuard;
        this.schedulingLockRepository = schedulingLockRepository;
    }

    @Transactional
    public SlotResponse createSlot(CreateSlotRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot details are required.");
        }
        // Validate before taking the scheduling lock so malformed input never holds it.
        SlotEntity slot = buildValidatedSlot(request);
        schedulingLockRepository.acquire();
        assertNewSlotsSafe(List.of(slot));
        return SlotResponse.from(slotRepository.save(slot));
    }

    @Transactional
    public List<SlotResponse> createSlots(BatchCreateSlotsRequest request) {
        if (request == null || request.slots() == null || request.slots().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one slot is required.");
        }
        // Validate the whole batch before locking; then take the scheduling lock ONCE and evaluate
        // every new slot against a single consistent proposed set, so the batch is all-or-nothing.
        List<SlotEntity> slots = request.slots().stream().map(this::buildValidatedSlot).toList();
        schedulingLockRepository.acquire();
        assertNewSlotsSafe(slots);
        return slots.stream().map(slot -> SlotResponse.from(slotRepository.save(slot))).toList();
    }

    // Parse + validate a create request into an unsaved entity (bookedCount 0). No DB access, so
    // it's safe to run before acquiring the scheduling lock.
    private SlotEntity buildValidatedSlot(CreateSlotRequest request) {
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
        return slot;
    }

    @Transactional
    public SlotResponse updateSlot(Long id, UpdateSlotRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Update details are required.");
        }

        // Take the scheduling lock before loading capacity, so a concurrent confirmation (which
        // also takes it) can't slip an appointment in between our safety check and our write.
        schedulingLockRepository.acquire();

        SlotEntity slot = loadOrThrow(id);

        String title = request.title() != null ? normalizeText(request.title()) : slot.getTitle();
        String description = request.description() != null ? normalizeText(request.description()) : slot.getDescription();
        Instant startTime = request.startTime() != null ? parseTime(request.startTime(), "Start time") : slot.getStartTime();
        Instant endTime = request.endTime() != null ? parseTime(request.endTime(), "End time") : slot.getEndTime();
        int capacity = request.capacity() != null ? request.capacity() : slot.getCapacity();

        validateSlotFields(title, startTime, endTime, capacity);

        boolean timeChanged = !startTime.equals(slot.getStartTime()) || !endTime.equals(slot.getEndTime());
        // A time change moves the slot's capacity tile; refuse it while any non-cancelled booking
        // still points here (its appointment would be silently rescheduled). Cancel those first.
        if (timeChanged && bookingRepository.existsNonCancelledBookingBySlotId(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot change the time of a slot that has bookings. Cancel the bookings first.");
        }

        // A capacity reduction or a time change could push existing appointments over capacity at
        // some instant (a capacity increase never can). Validate only the PROPOSED window
        // [startTime, endTime): removing the slot's OLD tile can only relax capacity (the min over
        // fewer covering tiles never decreases), so the old window can't newly violate — and this
        // avoids scanning unrelated time between the old and new windows on a move. Evaluated under
        // the COMPLETE proposed tile set (old version excluded, new version included once).
        if (timeChanged || capacity < slot.getCapacity()) {
            List<SchedulingGuard.CapacityTile> proposed = activeTilesExcluding(id, startTime, endTime);
            proposed.add(new SchedulingGuard.CapacityTile(startTime, endTime, capacity, id));
            if (!schedulingGuard.capacityChangeIsSafe(
                    activeAppointmentsBefore(endTime), proposed, startTime, endTime)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This change would leave existing appointments over capacity. Cancel some bookings first.");
            }
        }

        slot.setTitle(title);
        slot.setDescription(description);
        slot.setStartTime(startTime);
        slot.setEndTime(endTime);
        slot.setCapacity(capacity);

        try {
            // Flush now so a concurrent cancel/complete's optimistic-version change on this slot's
            // bookedCount surfaces here as a retryable 409, not a commit-time 500.
            return SlotResponse.from(slotRepository.saveAndFlush(slot));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Slot was modified concurrently. Please try again.");
        }
    }

    @Transactional
    public void deleteSlot(Long id) {
        // Take the scheduling lock before the existence check + delete. The V1 FK is ON DELETE
        // CASCADE, so an unlocked check-then-delete could race a confirmation and cascade-delete a
        // just-inserted booking. Under the lock: delete-first => the racing confirmation 404s;
        // confirm-first => this sees the booking and 409s. (Removing a capacity tile never creates
        // an over-capacity instant, so no SchedulingGuard check is needed here.)
        schedulingLockRepository.acquire();
        SlotEntity slot = loadOrThrow(id);
        // Bookings reference the slot by id. Block deletion while any non-cancelled (active or
        // completed) booking still points at it, so we never orphan a live/historical booking or
        // break the booking-list endpoints (which 404 on a missing slot). Cancelled bookings are
        // terminal, so clear them out with the slot.
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

    // Advisory availability for a quote: candidate slots (future, non-archived) whose start could
    // still admit an appointment of the quote's duration under SchedulingGuard, given the current
    // appointments and capacity tiles. Confirmation re-checks under the scheduling lock, so this is
    // a hint, not a guarantee (hence no lock here). serviceId is required; add-ons default to "none",
    // and an unknown/partial quote is a 400.
    public List<SlotResponse> listAvailableSlots(String serviceId, String nailArtId, String removalId) {
        int durationMin = BookingCatalog.totalDurationMin(serviceId, nailArtId, removalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A valid service selection is required to check availability."));

        Instant earliest = BookingWindow.earliestBookableStartUtc();
        // Bound the horizon so this public endpoint can't be made to load + sweep the entire future
        // schedule. Slots further out simply aren't offered until they fall inside the window.
        List<SlotEntity> candidates = slotRepository.findBookableCandidates(
                earliest, earliest.plus(AVAILABILITY_HORIZON));
        if (candidates.isEmpty()) {
            return List.of();
        }
        // Load appointments + tiles once over the whole candidate span; the guard clips to each
        // candidate's own window per call.
        Instant maxEnd = candidates.stream()
                .map(slot -> occupiedEndForCandidate(slot, durationMin))
                .max(Instant::compareTo).orElseThrow();
        List<SchedulingGuard.Appointment> appointments = activeAppointmentsBefore(maxEnd);
        List<SchedulingGuard.CapacityTile> tiles = activeTilesExcluding(null, earliest, maxEnd);
        return candidates.stream()
                .filter(slot -> schedulingGuard.canAddAppointment(
                        slot.getStartTime(), occupiedEndForCandidate(slot, durationMin), appointments, tiles))
                .map(SlotResponse::from)
                .toList();
    }

    private static Instant occupiedEndForCandidate(SlotEntity slot, int durationMin) {
        return durationMin > 0
                ? slot.getStartTime().plus(Duration.ofMinutes(durationMin))
                : slot.getEndTime();
    }

    public SlotResponse getSlot(Long id) {
        return SlotResponse.from(loadOrThrow(id));
    }

    SlotEntity loadOrThrow(Long id) {
        return slotRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found."));
    }

    // Reject a batch of new slots if adding their capacity tiles would leave any existing
    // appointment over capacity at some instant. Evaluated over the union of the new slots' windows
    // against the complete proposed set (existing active tiles + the new ones). Adding a tile can
    // lower capacity(t) = min over covering tiles, so a new low-capacity slot dropped into a busy
    // window can create a violation — hence the check.
    private void assertNewSlotsSafe(List<SlotEntity> newSlots) {
        if (newSlots.isEmpty()) {
            return;
        }
        // Load existing tiles + appointments once across the batch span, then build the COMPLETE
        // proposed set (existing + every new tile).
        Instant spanStart = newSlots.stream().map(SlotEntity::getStartTime).min(Instant::compareTo).orElseThrow();
        Instant spanEnd = newSlots.stream().map(SlotEntity::getEndTime).max(Instant::compareTo).orElseThrow();
        List<SchedulingGuard.CapacityTile> proposed = activeTilesExcluding(null, spanStart, spanEnd);
        for (SlotEntity slot : newSlots) {
            // New slots aren't persisted yet (id null); slotId is display-only in the guard.
            proposed.add(new SchedulingGuard.CapacityTile(slot.getStartTime(), slot.getEndTime(),
                    slot.getCapacity(), slot.getId()));
        }
        List<SchedulingGuard.Appointment> appointments = activeAppointmentsBefore(spanEnd);
        // Validate each new slot's OWN window, not the batch hull, so a pre-existing conflict in the
        // gap between two far-apart new slots can't reject an otherwise-harmless batch.
        for (SlotEntity slot : newSlots) {
            if (!schedulingGuard.capacityChangeIsSafe(appointments, proposed,
                    slot.getStartTime(), slot.getEndTime())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This slot would leave existing appointments over capacity at some time.");
            }
        }
    }

    // Active capacity tiles covering [windowStart, windowEnd), excluding the slot with excludedId
    // (its old version, replaced by a proposed one by the caller). excludedId may be null. Returns a
    // mutable list so the caller can append proposed tiles.
    private List<SchedulingGuard.CapacityTile> activeTilesExcluding(Long excludedId, Instant windowStart, Instant windowEnd) {
        List<SchedulingGuard.CapacityTile> tiles = new ArrayList<>();
        for (SlotEntity s : slotRepository.findActiveSlotsOverlapping(windowStart, windowEnd)) {
            if (excludedId == null || !excludedId.equals(s.getId())) {
                tiles.add(new SchedulingGuard.CapacityTile(s.getStartTime(), s.getEndTime(), s.getCapacity(), s.getId()));
            }
        }
        return tiles;
    }

    // Active BOOKED appointments that could overlap a window ending at windowEnd, mapped to guard
    // inputs. The guard clips to the query window, so a superset (everything starting before
    // windowEnd) is correct.
    private List<SchedulingGuard.Appointment> activeAppointmentsBefore(Instant windowEnd) {
        return bookingRepository.findActiveOccupiedIntervalsBefore(windowEnd).stream()
                .map(iv -> new SchedulingGuard.Appointment(iv.start(), iv.end(), iv.bookingId()))
                .toList();
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
