-- Joined display names of any selected repairs (Nail Fix / Single Nail Extension), or NULL.
-- Informational only — never priced or timed; see BookingCatalog.repair / BookingService.
ALTER TABLE tbl_bookings ADD COLUMN repairs VARCHAR(255);
