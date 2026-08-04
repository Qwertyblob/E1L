-- Comma-joined names of the repair add-ons selected at booking time (multi-select: "Nail Fix",
-- "Single Nail Extension"), resolved server-side from the booking catalog. Repairs are quoted and
-- fitted in on the day, so they deliberately contribute nothing to total_price or duration_min —
-- this column only records what the client asked for. NULL = no repairs requested (also every
-- booking made before this feature).
ALTER TABLE tbl_bookings ADD COLUMN repairs VARCHAR(255);
