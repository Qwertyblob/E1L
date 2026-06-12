-- Indexes for the archive paths added in V2, which only indexed the active subset.

-- Archived-slots listing: archived_at IS NOT NULL ORDER BY start_time DESC.
CREATE INDEX ix_slots_archived_start_time ON tbl_slots (start_time DESC) WHERE archived_at IS NOT NULL;

-- The nightly archival job filters slots by end_time (both directly and via the
-- booking-archival subquery, which scans all slots regardless of archived_at),
-- so this one is deliberately not partial.
CREATE INDEX ix_slots_end_time ON tbl_slots (end_time);
