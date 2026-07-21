-- Total appointment length in minutes (service + nail art + removal), computed server-side from
-- the booking catalog at creation. It defines the booking's occupied interval
-- [slot start, slot start + duration_min), which SchedulingGuard uses to enforce the
-- no-overlapping-appointment invariant at confirmation time. NULL = legacy row booked before this
-- feature; such rows fall back to occupying only their own slot's [start, end). V9 forbids
-- non-positive values.
ALTER TABLE tbl_bookings ADD COLUMN duration_min INTEGER;
