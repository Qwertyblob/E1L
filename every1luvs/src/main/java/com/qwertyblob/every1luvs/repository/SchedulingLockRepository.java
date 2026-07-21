package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.SchedulingLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface SchedulingLockRepository extends JpaRepository<SchedulingLock, Integer> {

    // SELECT ... FOR UPDATE on the single seeded row. Must run inside the caller's @Transactional;
    // the row stays held until that transaction commits or rolls back. Returns empty only if the
    // seeded row (id=1) is missing — see acquire().
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM SchedulingLock l WHERE l.id = 1")
    Optional<SchedulingLock> lockSingletonRow();

    // Take the singleton scheduling lock, serializing every concurrency-adding operation salon-wide.
    // Acquire this BEFORE any capacity read and BEFORE the per-user row lock — the fixed lock order
    // avoids deadlock. FAIL CLOSED: the row is seeded by V8 and never deleted, so its absence means a
    // broken schema/restore; refuse to run unserialized rather than silently skipping the lock.
    default void acquire() {
        lockSingletonRow().orElseThrow(() -> new IllegalStateException(
                "Scheduling lock row (id=" + SchedulingLock.SINGLETON_ID + ") is missing — "
                        + "refusing to run without serialization."));
    }
}
