package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.CatalogDocument;

/** Known-good catalogs for unit tests: the bundled first-boot seed (classic = S$58 / 45 min). */
final class TestCatalogs {

    static final long SEED_VERSION = 1L;

    private TestCatalogs() {
    }

    static CatalogDocument seedDocument() {
        return CatalogValidator.validate(CatalogService.loadSeedDocument(), null);
    }

    static BookingCatalog seed() {
        return BookingCatalog.of(SEED_VERSION, seedDocument());
    }
}
