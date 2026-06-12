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

    // Admin listing shows active (non-archived) slots only; archived slots stay in the
    // table so bookings that outlive them (1-year retention vs 3 months) still resolve.
    Page<SlotEntity> findByArchivedAtIsNullOrderByStartTimeAsc(Pageable pageable);

    @Query("SELECT s FROM SlotEntity s WHERE s.archivedAt IS NULL AND s.bookedCount < s.capacity "
            + "AND s.startTime >= :earliest ORDER BY s.startTime ASC")
    List<SlotEntity> findAvailableSlots(@Param("earliest") Instant earliest);

    // Bulk soft-archive of slots whose end_time passed the retention cutoff. Bypasses the
    // @Version check by design: archiving never races a seat change on a months-old slot.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE SlotEntity s SET s.archivedAt = :now "
            + "WHERE s.archivedAt IS NULL AND s.endTime < :cutoff")
    int archiveSlotsEndedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
