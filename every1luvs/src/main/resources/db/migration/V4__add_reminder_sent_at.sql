-- Reminder emails go out at most once per booking, ~2 days before the appointment.
-- reminder_sent_at stamps when the reminder was sent (NULL = not yet reminded); the
-- scheduled ReminderService only picks up bookings where it is still NULL, so a re-run
-- never double-sends.
ALTER TABLE tbl_bookings ADD COLUMN reminder_sent_at TIMESTAMP WITH TIME ZONE;

-- The reminder sweep scans active, not-yet-reminded BOOKED rows and joins to the slot's
-- start_time. This partial index keeps that scan tiny once most bookings are reminded.
CREATE INDEX ix_bookings_reminder_pending ON tbl_bookings (slot_id)
    WHERE reminder_sent_at IS NULL AND archived_at IS NULL AND status = 'BOOKED';
