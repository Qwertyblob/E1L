package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    // Row-level lock used to serialize a user's concurrent authenticated bookings so the
    // per-account active-booking cap can't be beaten by a count-then-insert race. Must run
    // inside the caller's @Transactional (BookingService.createBooking).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id")
    Optional<UserEntity> findByIdForUpdate(@Param("id") Long id);
}
