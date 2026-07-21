package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.SchedulingConflictResponse;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Pre-deployment integrity audit for the scheduling invariant. Loads the whole live schedule —
 * every active BOOKED appointment and every active capacity tile — and asks {@link SchedulingGuard}
 * for every sub-interval where concurrency already exceeds capacity. Read-only by design: it never
 * cancels or mutates a booking. Until an operator resolves (or explicitly grandfathers) the reported
 * conflicts, the guarantee is only "prevents new violations", not "invariant established".
 */
@Service
public class SchedulingAuditService {

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final SchedulingGuard schedulingGuard;

    public SchedulingAuditService(BookingRepository bookingRepository, SlotRepository slotRepository,
                                  SchedulingGuard schedulingGuard) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.schedulingGuard = schedulingGuard;
    }

    @Transactional(readOnly = true)
    public List<SchedulingConflictResponse> findConflicts() {
        List<SchedulingGuard.Appointment> appointments = bookingRepository.findAllActiveOccupiedIntervals().stream()
                .map(iv -> new SchedulingGuard.Appointment(iv.start(), iv.end(), iv.bookingId()))
                .toList();
        List<SchedulingGuard.CapacityTile> tiles = slotRepository.findActiveSlots().stream()
                .map(s -> new SchedulingGuard.CapacityTile(s.getStartTime(), s.getEndTime(), s.getCapacity(), s.getId()))
                .toList();
        return schedulingGuard.findViolations(appointments, tiles).stream()
                .map(v -> new SchedulingConflictResponse(v.from(), v.to(), v.concurrency(), v.capacity(),
                        v.bookingIds(), v.slotIds()))
                .toList();
    }
}
