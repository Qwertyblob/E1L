-- Service photos shown on the landing page, uploaded by the admin in the catalog editor.
-- Each catalog service references one by id (CatalogDocument.ServiceItem.imageId). Uploads are
-- re-encoded server-side (ImageSanitizer) and stored content-addressed (id = SHA-256 of the
-- stored bytes), so a given id's bytes never change and can be cached forever. The first-boot
-- seed photos use fixed "seed-<service>" ids. Rows are never updated; unused ones are harmless.
CREATE TABLE tbl_catalog_images (
    id           VARCHAR(64)              PRIMARY KEY,
    content_type VARCHAR(32)              NOT NULL,
    data         BYTEA                    NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE,
    created_by   VARCHAR(255)
);
