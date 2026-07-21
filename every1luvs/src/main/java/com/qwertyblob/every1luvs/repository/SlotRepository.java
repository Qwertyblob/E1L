package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.SlotEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SlotRepository extends JpaRepository<SlotEntity, Long> {

    // Admin listing returns active slots only; archived slots live in their own
    // paginated listing below, fetched on demand so the (ever-growing) archive
    // isn't shipped with every slot-list request.
    Page<SlotEntity> findByArchivedAtIsNullOrderByStartTimeAsc(Pageable pageable);

    // Newest-ended first: the recently archived slots are the ones admins look for.
    Page<SlotEntity> findByArchivedAtIsNotNullOrderByStartTimeDesc(Pageable pageable);

    // Bookable candidate slots: non-archived and starting within the bounded window
    // [earliest, horizonEnd), with NO bookedCount condition — availability is decided by
    // SchedulingGuard against the chosen service duration, not by the display counter (a short slot
    // may still admit a booking whose duration overlaps neighbouring capacity). The horizon bound
    // caps how much the public availability endpoint loads and sweeps per request. Ordered for
    // stable output.
    @Query("SELECT s FROM SlotEntity s WHERE s.archivedAt IS NULL "
            + "AND s.startTime >= :earliest AND s.startTime < :horizonEnd ORDER BY s.startTime ASC")
    List<SlotEntity> findBookableCandidates(@Param("earliest") Instant earliest,
                                            @Param("horizonEnd") Instant horizonEnd);

    // Active slots whose window intersects [windowStart, windowEnd) — i.e. startTime < windowEnd
    // AND endTime > windowStart (half-open, so a slot that merely abuts the boundary doesn't
    // count). These are the capacity tiles SchedulingGuard evaluates concurrency against; the
    // picked slot (whose window is the query window) is included. Ordered for stable/testable
    // output only.
    @Query("SELECT s FROM SlotEntity s WHERE s.archivedAt IS NULL "
            + "AND s.startTime < :windowEnd AND s.endTime > :windowStart "
            + "ORDER BY s.startTime ASC")
    List<SlotEntity> findActiveSlotsOverlapping(@Param("windowStart") Instant windowStart,
                                                @Param("windowEnd") Instant windowEnd);

    // Every active (non-archived) slot as a capacity tile source, for the pre-deployment conflict
    // audit. Unbounded on purpose — the audit inspects the whole live schedule.
    @Query("SELECT s FROM SlotEntity s WHERE s.archivedAt IS NULL ORDER BY s.startTime ASC")
    List<SlotEntity> findActiveSlots();

    // Bulk soft-archive of slots whose end_time passed the retention cutoff. Bypasses the
    // @Version check by design: archiving never races a seat change on a months-old slot.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SlotEntity s SET s.archivedAt = :now "
            + "WHERE s.archivedAt IS NULL AND s.endTime < :cutoff")
    int archiveSlotsEndedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
