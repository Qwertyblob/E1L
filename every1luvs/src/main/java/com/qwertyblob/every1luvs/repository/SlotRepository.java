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

    @Query("SELECT s FROM SlotEntity s WHERE s.archivedAt IS NULL AND s.bookedCount < s.capacity "
            + "AND s.startTime >= :earliest ORDER BY s.startTime ASC")
    List<SlotEntity> findAvailableSlots(@Param("earliest") Instant earliest);

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

    // Bulk soft-archive of slots whose end_time passed the retention cutoff. Bypasses the
    // @Version check by design: archiving never races a seat change on a months-old slot.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SlotEntity s SET s.archivedAt = :now "
            + "WHERE s.archivedAt IS NULL AND s.endTime < :cutoff")
    int archiveSlotsEndedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
