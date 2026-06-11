-- Baseline schema (consolidated end-state of the former replayed schema.sql).
-- Postgres-only: integration tests run H2 with ddl-auto=create-drop and Flyway disabled.
-- Existing databases that already carry this schema are baselined at version 1
-- (spring.flyway.baseline-on-migrate=true), so this script only runs on empty databases.

CREATE TABLE tbl_users (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL,
    password            VARCHAR(255) NOT NULL,
    role                VARCHAR(255) NOT NULL,
    verify_otp          VARCHAR(255),
    is_verified_account BOOLEAN      NOT NULL DEFAULT FALSE,
    verify_otp_expire_at BIGINT      NOT NULL DEFAULT 0,
    reset_otp           VARCHAR(255),
    reset_otp_expire_at BIGINT       NOT NULL DEFAULT 0,
    -- Epoch MILLIS of the last password change/reset. Auth tokens issued at or before
    -- this are rejected, so changing/resetting a password invalidates all previously-
    -- issued cookies (see TokenAuthenticationFilter).
    password_changed_at BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX ux_tbl_users_email ON tbl_users (email);

CREATE TABLE tbl_slots (
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

CREATE TABLE tbl_bookings (
    id             BIGSERIAL PRIMARY KEY,
    slot_id        BIGINT                   NOT NULL,
    -- NULL for guest bookings; contact details live in the customer_* columns.
    user_id        BIGINT,
    status         VARCHAR(32)              NOT NULL DEFAULT 'BOOKED',
    customer_name  VARCHAR(255),
    customer_email VARCHAR(255),
    phone          VARCHAR(64),
    instagram      VARCHAR(255),
    notes          TEXT,
    service_name   VARCHAR(255),
    technician     VARCHAR(255),
    nail_art       VARCHAR(255),
    removal        VARCHAR(255),
    total_price    INT,
    created_at     TIMESTAMP WITH TIME ZONE,
    updated_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_bookings_slot    FOREIGN KEY (slot_id) REFERENCES tbl_slots(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookings_user    FOREIGN KEY (user_id) REFERENCES tbl_users(id) ON DELETE CASCADE,
    CONSTRAINT chk_bookings_status CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED'))
);

-- One active booking per registered user per slot.
CREATE UNIQUE INDEX uq_bookings_active_user_slot
    ON tbl_bookings (slot_id, user_id) WHERE status = 'BOOKED';

-- Guest equivalent: user_id is NULL for guests (NULLs never collide in the index above),
-- so dedupe active guest bookings by slot + normalized (lower-cased) email instead.
CREATE UNIQUE INDEX uq_bookings_active_guest_slot_email
    ON tbl_bookings (slot_id, lower(customer_email))
    WHERE user_id IS NULL AND status = 'BOOKED';

CREATE INDEX ix_bookings_slot_status ON tbl_bookings (slot_id, status);
CREATE INDEX ix_bookings_user_id     ON tbl_bookings (user_id);
