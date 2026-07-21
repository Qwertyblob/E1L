package com.qwertyblob.every1luvs.repository;

import com.qwertyblob.every1luvs.dto.OccupiedInterval;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    // Customer/admin listings hide archived bookings, so year-old bookings appear
    // deleted in the frontend while the rows stay in the table.
    List<BookingEntity> findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(Long userId);

    @Query("SELECT b FROM BookingEntity b WHERE b.id = :id AND b.userId = :userId AND b.status = 'BOOKED'")
    Optional<BookingEntity> findActiveBookingByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

    // Archived bookings appear deleted everywhere in the UI, so admin cancel/complete
    // must not be able to reach them by ID either.
    Optional<BookingEntity> findByIdAndStatusAndArchivedAtIsNull(Long id, String status);

    // Lookups used to distinguish 404 (no such visible booking / not owned) from 409 (visible but
    // no longer BOOKED) after an atomic guarded status transition changes zero rows.
    Optional<BookingEntity> findByIdAndArchivedAtIsNull(Long id);

    Optional<BookingEntity> findByIdAndUserIdAndArchivedAtIsNull(Long id, Long userId);

    // Active BOOKED bookings that could overlap a scheduling window ending at :windowEnd, projected
    // as the technician-seat interval each occupies. Filter is slot start < windowEnd: an
    // appointment starting at/after the window end can't overlap it (half-open). SchedulingGuard
    // clips to the query window, so appointments that already ended contribute nothing. We can't
    // narrow by slot end — durationMin may exceed the slot length (short slots are bookable) — so
    // the occupied interval can run past the slot's own end. Feeds the confirmation guard.
    @Query("SELECT new com.qwertyblob.every1luvs.dto.OccupiedInterval(b.id, s.startTime, s.endTime, b.durationMin) "
            + "FROM BookingEntity b, SlotEntity s WHERE b.slotId = s.id "
            + "AND b.status = 'BOOKED' AND b.archivedAt IS NULL AND s.startTime < :windowEnd")
    List<OccupiedInterval> findActiveOccupiedIntervalsBefore(@Param("windowEnd") Instant windowEnd);

    // Every active BOOKED booking as its occupied interval, for the pre-deployment conflict audit
    // (SchedulingGuard.findViolations sweeps them against the active capacity tiles). Unbounded on
    // purpose — the audit inspects the whole live schedule.
    @Query("SELECT new com.qwertyblob.every1luvs.dto.OccupiedInterval(b.id, s.startTime, s.endTime, b.durationMin) "
            + "FROM BookingEntity b, SlotEntity s WHERE b.slotId = s.id "
            + "AND b.status = 'BOOKED' AND b.archivedAt IS NULL")
    List<OccupiedInterval> findAllActiveOccupiedIntervals();

    // Count a user's still-upcoming held seats (future BOOKED, non-archived). Past BOOKED rows are
    // excluded so a client is never permanently blocked by an old appointment left un-completed.
    @Query("SELECT COUNT(b) FROM BookingEntity b, SlotEntity s WHERE b.slotId = s.id "
            + "AND b.userId = :userId AND b.status = 'BOOKED' AND b.archivedAt IS NULL "
            + "AND s.startTime > :now")
    long countActiveFutureBookingsForUser(@Param("userId") Long userId, @Param("now") Instant now);

    // Atomic guarded status transition (admin): flips a BOOKED booking to :newStatus and reports how
    // many rows changed (1 = success, 0 = it was concurrently changed away from BOOKED). Replaces
    // read-then-save so a cancel/complete race can't both "win". clearAutomatically keeps the
    // persistence context from serving a stale copy afterwards.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BookingEntity b SET b.status = :newStatus "
            + "WHERE b.id = :id AND b.status = 'BOOKED' AND b.archivedAt IS NULL")
    int transitionFromBooked(@Param("id") Long id, @Param("newStatus") String newStatus);

    // Atomic guarded status transition (customer): as above but scoped to the owning user so a
    // customer can only transition their own booking.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BookingEntity b SET b.status = :newStatus "
            + "WHERE b.id = :id AND b.userId = :userId AND b.status = 'BOOKED' AND b.archivedAt IS NULL")
    int transitionFromBookedForUser(@Param("id") Long id, @Param("userId") Long userId,
                                    @Param("newStatus") String newStatus);

    // A slot can't be deleted while a non-cancelled (active or completed) booking still
    // points at it; cancelled bookings are terminal and get cleaned up with the slot.
    // Archived bookings don't count — they're invisible in the UI, and letting them
    // block deletion would make archived slots permanently undeletable.
    @Query("SELECT COUNT(b) > 0 FROM BookingEntity b WHERE b.slotId = :slotId "
            + "AND b.status <> 'CANCELLED' AND b.archivedAt IS NULL")
    boolean existsNonCancelledBookingBySlotId(@Param("slotId") Long slotId);

    void deleteBySlotId(Long slotId);

    // Every booking ever made by a user, regardless of status or archive state — used when
    // deleting an account so seats held by still-active bookings can be released first.
    List<BookingEntity> findByUserId(Long userId);

    void deleteByUserId(Long userId);

    Page<BookingEntity> findByArchivedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    // Bulk soft-archive of bookings whose appointment (slot end_time) passed the
    // retention cutoff. Keyed off the slot's end time — not the booking's created_at —
    // so "1 year" means one year after the visit, regardless of how early it was booked.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE BookingEntity b SET b.archivedAt = :now WHERE b.archivedAt IS NULL "
            + "AND b.slotId IN (SELECT s.id FROM SlotEntity s WHERE s.endTime < :cutoff)")
    int archiveBookingsForSlotsEndedBefore(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

    // Active BOOKED bookings, not yet reminded, whose appointment (slot start_time) falls in
    // the reminder window (now, windowEnd] — i.e. still upcoming and within ~2 days. Joined to
    // the slot since BookingEntity holds slot_id as a plain value, not a mapped relation.
    @Query("SELECT b FROM BookingEntity b, SlotEntity s WHERE b.slotId = s.id "
            + "AND b.status = 'BOOKED' AND b.archivedAt IS NULL AND b.reminderSentAt IS NULL "
            + "AND s.startTime > :now AND s.startTime <= :windowEnd "
            + "ORDER BY s.startTime ASC")
    List<BookingEntity> findDueForReminder(@Param("now") Instant now, @Param("windowEnd") Instant windowEnd);

    // Stamp a single booking as reminded, in its own short transaction, only if it hasn't been
    // already (the IS NULL guard makes this idempotent — a concurrent/duplicate sweep updates
    // zero rows). Called after a confirmed send so a booking is reminded at least once.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE BookingEntity b SET b.reminderSentAt = :now "
            + "WHERE b.id = :id AND b.reminderSentAt IS NULL")
    int markReminderSent(@Param("id") Long id, @Param("now") Instant now);

    // COMPLETED bookings not yet asked for a review, whose appointment (slot end_time) is recent
    // (>= recencyFloor). Only COMPLETED rows qualify, so no-shows/cancellations are excluded; the
    // recency floor stops the fallback sweep from backfilling months-old clients when the review
    // link is first configured. Joined to the slot for the email's appointment details.
    @Query("SELECT b FROM BookingEntity b, SlotEntity s WHERE b.slotId = s.id "
            + "AND b.status = 'COMPLETED' AND b.archivedAt IS NULL AND b.reviewSentAt IS NULL "
            + "AND s.endTime >= :recencyFloor "
            + "ORDER BY s.endTime ASC")
    List<BookingEntity> findDueForReview(@Param("recencyFloor") Instant recencyFloor);

    // Stamp a single booking as review-asked, in its own short transaction, only if it hasn't been
    // already (the IS NULL guard makes this idempotent — a race between the immediate on-complete
    // send and the fallback sweep updates zero rows the second time). Called after a confirmed send.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE BookingEntity b SET b.reviewSentAt = :now "
            + "WHERE b.id = :id AND b.reviewSentAt IS NULL")
    int markReviewSent(@Param("id") Long id, @Param("now") Instant now);

    // COMPLETED bookings not yet nudged to rebook, whose appointment (slot end_time) is due — at
    // least the rebooking delay ago (endTime <= dueBefore) but no older than the grace floor
    // (endTime >= floor). Only COMPLETED rows qualify, so no-shows/cancellations are excluded; the
    // bounded window stops the sweep from backfilling a year of clients on first enable and lets a
    // missed run self-heal. Joined to the slot for the email.
    @Query("SELECT b FROM BookingEntity b, SlotEntity s WHERE b.slotId = s.id "
            + "AND b.status = 'COMPLETED' AND b.archivedAt IS NULL AND b.rebookingSentAt IS NULL "
            + "AND s.endTime <= :dueBefore AND s.endTime >= :floor "
            + "ORDER BY s.endTime ASC")
    List<BookingEntity> findDueForRebooking(@Param("dueBefore") Instant dueBefore, @Param("floor") Instant floor);

    // Stamp a single booking as rebooking-nudged, in its own short transaction, only if it hasn't
    // been already (the IS NULL guard makes this idempotent — a concurrent sweep updates zero rows
    // the second time). Called after a confirmed send.
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE BookingEntity b SET b.rebookingSentAt = :now "
            + "WHERE b.id = :id AND b.rebookingSentAt IS NULL")
    int markRebookingSent(@Param("id") Long id, @Param("now") Instant now);
}
