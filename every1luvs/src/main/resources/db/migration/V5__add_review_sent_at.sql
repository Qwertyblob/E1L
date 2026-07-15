-- Post-appointment review-request emails go out at most once per booking, and only for
-- COMPLETED bookings (a no-show is never marked completed, so it is never asked to review).
-- review_sent_at stamps when the request was sent (NULL = not yet sent); ReviewRequestService
-- only picks up bookings where it is still NULL, so a re-run never double-sends.
ALTER TABLE tbl_bookings ADD COLUMN review_sent_at TIMESTAMP WITH TIME ZONE;

-- The review sweep scans completed, not-yet-asked bookings and joins to the slot's end_time.
-- This partial index keeps that scan tiny once most bookings have been asked.
CREATE INDEX ix_bookings_review_pending ON tbl_bookings (slot_id)
    WHERE review_sent_at IS NULL AND archived_at IS NULL AND status = 'COMPLETED';
