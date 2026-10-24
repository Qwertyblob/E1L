package com.qwertyblob.every1luvs.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The live catalog as customers see it (hidden items removed). {@code version} is the published
 * catalog id: the booking flow sends back the version the customer reviewed and accepted, and the
 * server rejects the booking (409 CATALOG_VERSION_MISMATCH) if prices have changed since.
 */
public record PublicCatalogResponse(
        long version,
        @JsonProperty("NAIL_SERVICES") List<CatalogDocument.ServiceItem> nailServices,
        @JsonProperty("NAIL_ART") List<CatalogDocument.AddOnItem> nailArt,
        @JsonProperty("REMOVAL") List<CatalogDocument.AddOnItem> removal,
        @JsonProperty("REPAIRS") List<CatalogDocument.RepairItem> repairs
) {
    public static PublicCatalogResponse of(long version, CatalogDocument document) {
        CatalogDocument visible = document.visibleOnly();
        return new PublicCatalogResponse(version, visible.nailServices(), visible.nailArt(),
                visible.removal(), visible.repairs());
    }
}
