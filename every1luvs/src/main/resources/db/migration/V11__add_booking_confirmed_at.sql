-- Client confirmation emails now go out only when the admin confirms a booking (after seeing
-- the deposit). confirmed_at stamps when that happened (NULL = pending / awaiting deposit).
-- Capacity is still governed by status = 'BOOKED', so a pending booking already holds its seat;
-- this column only gates the confirmation email and the admin's Pending vs Upcoming split.
ALTER TABLE tbl_bookings ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE;

-- Every pre-existing booking predates this feature and was effectively already confirmed, so
-- backfill it out of the Pending tab. created_at is nullable, so fall back to updated_at / now.
UPDATE tbl_bookings
   SET confirmed_at = COALESCE(created_at, updated_at, CURRENT_TIMESTAMP)
 WHERE confirmed_at IS NULL;
