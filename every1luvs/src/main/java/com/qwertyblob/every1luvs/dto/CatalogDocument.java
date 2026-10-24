package com.qwertyblob.every1luvs.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A full catalog snapshot — the JSON stored per row in {@code tbl_catalog_versions} and edited by
 * the admin catalog editor. Section keys keep the historic upper-case names the SPA renders from.
 *
 * <p>Boxed numbers/booleans so a missing field reaches {@code CatalogValidator} (which rejects a
 * missing price/duration with a clear 400) instead of failing deserialization opaquely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogDocument(
        @JsonProperty("NAIL_SERVICES") List<ServiceItem> nailServices,
        @JsonProperty("NAIL_ART") List<AddOnItem> nailArt,
        @JsonProperty("REMOVAL") List<AddOnItem> removal,
        @JsonProperty("REPAIRS") List<RepairItem> repairs
) {

    /** A bookable service. {@code price} is whole SGD; {@code durationMin} its appointment length. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceItem(String id, String label, String name, String desc,
                              Integer price, Integer durationMin, Boolean popular, Boolean hidden) {
        public boolean hiddenFromCustomers() {
            return Boolean.TRUE.equals(hidden);
        }
    }

    /** A priced single-select add-on (nail-art tier or removal option); durationMin is extra time. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AddOnItem(String id, String name, String sub, Integer price, Integer durationMin,
                            Boolean hidden) {
        public boolean hiddenFromCustomers() {
            return Boolean.TRUE.equals(hidden);
        }
    }

    /**
     * A multi-select repair. Repairs are quoted in person, so they carry no price/duration — only
     * a display-only {@code priceNote} (e.g. "S$3/nail"). {@code sub} is the booking-modal line,
     * {@code desc} the landing-page description.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepairItem(String id, String name, String sub, String desc, String priceNote,
                             Boolean hidden) {
        public boolean hiddenFromCustomers() {
            return Boolean.TRUE.equals(hidden);
        }
    }

    /** The customer-facing view: same document with hidden items removed. */
    public CatalogDocument visibleOnly() {
        return new CatalogDocument(
                nailServices.stream().filter(item -> !item.hiddenFromCustomers()).toList(),
                nailArt.stream().filter(item -> !item.hiddenFromCustomers()).toList(),
                removal.stream().filter(item -> !item.hiddenFromCustomers()).toList(),
                repairs.stream().filter(item -> !item.hiddenFromCustomers()).toList());
    }
}
