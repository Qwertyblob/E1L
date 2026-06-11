CREATE TABLE IF NOT EXISTS tbl_users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    verify_otp VARCHAR(255),
    is_verified_account BOOLEAN NOT NULL DEFAULT FALSE,
    verify_otp_expire_at BIGINT NOT NULL DEFAULT 0,
    reset_otp VARCHAR(255),
    reset_otp_expire_at BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_tbl_users_email ON tbl_users (email);

ALTER TABLE tbl_users ALTER COLUMN is_verified_account SET DEFAULT FALSE;

-- Epoch seconds of the last password change/reset. Auth tokens issued before this are
-- rejected, so changing/resetting a password invalidates all previously-issued cookies.
ALTER TABLE tbl_users ADD COLUMN IF NOT EXISTS password_changed_at BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS tbl_slots (
    id           BIGSERIAL PRIMARY KEY,
    title        VARCHAR(255)             NOT NULL,
    description  TEXT,
    start_time   TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time     TIMESTAMP WITH TIME ZONE NOT NULL,
    capacity     INT                      NOT NULL DEFAULT 1,
    booked_count INT                      NOT NULL DEFAULT 0,
    version      BIGINT                   NOT NULL DEFAULT 0,
    created_at   TIMESTAMP WITH TIME ZONE,
    updated_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_slots_capacity     CHECK (capacity >= 1),
    CONSTRAINT chk_slots_times        CHECK (end_time > start_time),
    CONSTRAINT chk_slots_booked_count CHECK (booked_count >= 0)
);

CREATE TABLE IF NOT EXISTS tbl_bookings (
    id         BIGSERIAL PRIMARY KEY,
    slot_id    BIGINT                   NOT NULL,
    user_id    BIGINT                   NOT NULL,
    status     VARCHAR(32)              NOT NULL DEFAULT 'BOOKED',
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_bookings_slot   FOREIGN KEY (slot_id) REFERENCES tbl_slots(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookings_user   FOREIGN KEY (user_id) REFERENCES tbl_users(id) ON DELETE CASCADE,
    CONSTRAINT chk_bookings_status CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED'))
);

-- Allow bookings to be marked COMPLETED (in addition to BOOKED / CANCELLED).
ALTER TABLE tbl_bookings DROP CONSTRAINT IF EXISTS chk_bookings_status;
ALTER TABLE tbl_bookings ADD CONSTRAINT chk_bookings_status CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED'));

-- Guest bookings: user_id is optional and customer contact details are stored inline.
ALTER TABLE tbl_bookings ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS customer_name  VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS customer_email VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS phone          VARCHAR(64);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS instagram      VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS notes          TEXT;

-- Booking line-item details (service chosen, technician level, add-ons, computed price).
-- Mirrors BookingEntity; required so inserts/startup don't fail under ddl-auto=validate|none.
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS service_name   VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS technician     VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS nail_art       VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS removal        VARCHAR(255);
ALTER TABLE tbl_bookings ADD COLUMN IF NOT EXISTS total_price    INT;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bookings_active_user_slot
    ON tbl_bookings (slot_id, user_id) WHERE status = 'BOOKED';

-- Guest equivalent of the index above: user_id is NULL for guests (and NULLs never
-- collide in the index above), so dedupe active guest bookings by slot + normalized
-- (lower-cased) email instead, blocking one email from holding several seats in a slot.
CREATE UNIQUE INDEX IF NOT EXISTS uq_bookings_active_guest_slot_email
    ON tbl_bookings (slot_id, lower(customer_email))
    WHERE user_id IS NULL AND status = 'BOOKED';

CREATE INDEX IF NOT EXISTS ix_bookings_slot_status ON tbl_bookings (slot_id, status);
CREATE INDEX IF NOT EXISTS ix_bookings_user_id     ON tbl_bookings (user_id);
