package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.SlotEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SlotRepository extends JpaRepository<SlotEntity, Long> {

    Page<SlotEntity> findAllByOrderByStartTimeAsc(Pageable pageable);

    @Query("SELECT s FROM SlotEntity s WHERE s.bookedCount < s.capacity "
            + "AND s.startTime >= :earliest ORDER BY s.startTime ASC")
    List<SlotEntity> findAvailableSlots(@Param("earliest") Instant earliest);
}
