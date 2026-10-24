package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.CatalogVersionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CatalogVersionRepository extends JpaRepository<CatalogVersionEntity, Long> {

    Optional<CatalogVersionEntity> findByStatus(String status);

    boolean existsByStatus(String status);

    // SELECT ... FOR UPDATE on the single row with this status (the partial unique indexes keep
    // PUBLISHED and DRAFT to one row each). Serializes draft create/save/discard/publish so the
    // admin's expected id/version check and the write that follows can't interleave.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CatalogVersionEntity c WHERE c.status = :status")
    Optional<CatalogVersionEntity> findByStatusForUpdate(@Param("status") String status);
}
