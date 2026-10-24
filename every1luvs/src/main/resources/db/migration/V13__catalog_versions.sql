-- Admin-editable service catalog (services, nail art, removal, repairs + prices/durations).
-- Each row is a full catalog snapshot as JSON. Exactly one row is PUBLISHED (the live catalog
-- the site renders and BookingService prices against); at most one row is the admin's DRAFT,
-- which becomes PUBLISHED on publish while the previous live row is kept as SUPERSEDED history.
-- The published row's id is the catalog version a customer's booking is checked against.
--
-- No seed rows here: on first boot CatalogService inserts the PUBLISHED row from the bundled
-- classpath seed (catalog/seed-services.json), so the live catalog starts as today's prices.
CREATE TABLE tbl_catalog_versions (
    id                BIGSERIAL PRIMARY KEY,
    status            VARCHAR(16)              NOT NULL,
    content           TEXT                     NOT NULL,
    version           BIGINT                   NOT NULL DEFAULT 0,
    -- The PUBLISHED row a DRAFT was started from; publish refuses if the live row has moved on.
    base_published_id BIGINT REFERENCES tbl_catalog_versions (id),
    created_at        TIMESTAMP WITH TIME ZONE,
    updated_at        TIMESTAMP WITH TIME ZONE,
    updated_by        VARCHAR(255),
    published_at      TIMESTAMP WITH TIME ZONE,
    published_by      VARCHAR(255),
    CONSTRAINT chk_catalog_versions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED'))
);

-- At most one live catalog and at most one working draft at any time. These are the real guard
-- against two admins racing a draft create / publish; the service-level checks only give a
-- friendlier 409 first.
CREATE UNIQUE INDEX uq_catalog_versions_one_published ON tbl_catalog_versions (status)
    WHERE status = 'PUBLISHED';
CREATE UNIQUE INDEX uq_catalog_versions_one_draft ON tbl_catalog_versions (status)
    WHERE status = 'DRAFT';
