-- Reset booked_count to its one authoritative meaning: the number of active (non-archived) BOOKED
-- bookings that pick THIS slot directly. The retired overlap-lock cache could have inflated the
-- counter by also counting bookings whose duration merely overlapped the slot, so recompute every
-- slot from the bookings table. booked_count is display-only — SchedulingGuard is the sole
-- authority for capacity/time safety — but it must still reflect the direct-booking count.
UPDATE tbl_slots s SET booked_count = (
    SELECT COUNT(*) FROM tbl_bookings b
    WHERE b.slot_id = s.id
      AND b.status = 'BOOKED'
      AND b.archived_at IS NULL
);
