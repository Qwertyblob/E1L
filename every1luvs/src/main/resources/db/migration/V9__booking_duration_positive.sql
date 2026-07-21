-- duration_min is the appointment length that defines a booking's occupied interval
-- [slotStart, slotStart + duration_min). The model treats null OR non-positive as "legacy": fall
-- back to the slot's own [start, end). Collapse any zero/negative values written before this
-- constraint into null so the two "legacy" cases are represented one way, then forbid non-positive
-- values going forward (null still allowed for genuinely pre-duration rows).
UPDATE tbl_bookings SET duration_min = NULL WHERE duration_min <= 0;

ALTER TABLE tbl_bookings
    ADD CONSTRAINT ck_bookings_duration_positive CHECK (duration_min IS NULL OR duration_min > 0);
