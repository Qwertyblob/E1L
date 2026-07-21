package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.SchedulingLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SchedulingLockRepository extends JpaRepository<SchedulingLock, Integer> {

    // Take the singleton scheduling lock (SELECT ... FOR UPDATE on the single seeded row). Must run
    // inside the caller's @Transactional; the row stays held until that transaction commits or rolls
    // back, serializing every concurrency-adding operation salon-wide. Acquire this BEFORE any
    // capacity read and BEFORE the per-user row lock — the fixed lock order avoids deadlock. The
    // returned value is unused; the lock is the SELECT's side effect.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM SchedulingLock l WHERE l.id = 1")
    Optional<SchedulingLock> acquire();
}
