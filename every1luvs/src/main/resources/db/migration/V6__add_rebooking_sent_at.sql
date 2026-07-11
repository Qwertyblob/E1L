-- Rebooking-prompt emails go out once per booking, ~3 weeks after a COMPLETED appointment (a
-- no-show is never marked completed, so it is never nudged to rebook). rebooking_sent_at stamps
-- when the prompt was sent (NULL = not yet sent); RebookingService only picks up bookings where
-- it is still NULL, so a re-run never double-sends.
ALTER TABLE tbl_bookings ADD COLUMN rebooking_sent_at TIMESTAMP WITH TIME ZONE;

-- The rebooking sweep scans completed, not-yet-nudged bookings and joins to the slot's end_time.
-- This partial index keeps that scan tiny once most bookings have been nudged.
CREATE INDEX ix_bookings_rebooking_pending ON tbl_bookings (slot_id)
    WHERE rebooking_sent_at IS NULL AND archived_at IS NULL AND status = 'COMPLETED';
