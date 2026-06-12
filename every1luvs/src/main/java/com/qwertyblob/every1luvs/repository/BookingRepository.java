package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.entity.BookingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<BookingEntity, Long> {

    @Query("SELECT COUNT(b) > 0 FROM BookingEntity b WHERE b.slotId = :slotId AND b.userId = :userId AND b.status = 'BOOKED'")
    boolean existsActiveBookingForUserAndSlot(@Param("slotId") Long slotId, @Param("userId") Long userId);

    // Guest bookings have userId = null, so the user/slot check above never fires for them.
    // Match on normalized (case-insensitive) customer email + slot to stop one guest email
    // from holding several seats in the same slot. Backed by the partial unique index
    // uq_bookings_active_guest_slot_email for defence in depth against races.
    @Query("SELECT COUNT(b) > 0 FROM BookingEntity b WHERE b.slotId = :slotId AND b.userId IS NULL "
            + "AND LOWER(b.customerEmail) = LOWER(:email) AND b.status = 'BOOKED'")
    boolean existsActiveGuestBookingForSlotAndEmail(@Param("slotId") Long slotId, @Param("email") String email);

    // Customer/admin listings show active (non-archived) bookings only.
    List<BookingEntity> findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(Long userId);

    @Query("SELECT b FROM BookingEntity b WHERE b.id = :id AND b.userId = :userId AND b.status = 'BOOKED'")
    Optional<BookingEntity> findActiveBookingByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    Optional<BookingEntity> findByIdAndStatus(Long id, String status);

    // A slot can't be deleted while a non-cancelled (active or completed) booking still
    // points at it; cancelled bookings are terminal and get cleaned up with the slot.
    @Query("SELECT COUNT(b) > 0 FROM BookingEntity b WHERE b.slotId = :slotId AND b.status <> 'CANCELLED'")
    boolean existsNonCancelledBookingBySlotId(@Param("slotId") Long slotId);

    void deleteBySlotId(Long slotId);

    Page<BookingEntity> findByArchivedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    // Bulk soft-archive of bookings whose appointment (slot end_time) passed the
    // retention cutoff. Keyed off the slot's end time — not the booking's created_at —
    // so "1 year" means one year after the visit, regardless of how early it was booked.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BookingEntity b SET b.archivedAt = :now WHERE b.archivedAt IS NULL "
            + "AND b.slotId IN (SELECT s.id FROM SlotEntity s WHERE s.endTime < :cutoff)")
    int archiveBookingsForSlotsEndedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);
}
