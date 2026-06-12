-- Soft-archive support. Rows are never moved to separate archive tables because
-- tbl_bookings references tbl_slots and slots archive earlier (3 months after
-- end_time) than bookings (1 year after their slot's end_time) — physically moving
-- a slot would orphan bookings that are still active. archived_at IS NULL = active;
-- a timestamp records when ArchivalService archived the row.

ALTER TABLE tbl_slots    ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tbl_bookings ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;

-- Listing queries filter on archived_at IS NULL; partial indexes keep the active
-- subset cheap to scan as archived rows accumulate.
CREATE INDEX ix_slots_active_start_time  ON tbl_slots (start_time)  WHERE archived_at IS NULL;
CREATE INDEX ix_bookings_active_created  ON tbl_bookings (created_at) WHERE archived_at IS NULL;
