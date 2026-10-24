package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.CatalogDocument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * An immutable snapshot of one PUBLISHED catalog version: the authoritative prices, durations and
 * display names the backend prices a booking against (it never trusts client-supplied values).
 *
 * <p>Instances come from {@link CatalogService#current()}. A booking calculation takes ONE instance
 * and uses it throughout, so every name, price and duration in a booking — and the
 * {@link #versionId()} checked against the customer's reviewed quote — come from the same version
 * even if an admin publishes mid-request.
 *
 * <p>Hidden items are excluded, so a hidden id is rejected (400) exactly like an unknown one.
 */
public final class BookingCatalog {

    /**
     * A bookable service with its price (SGD, whole dollars).
     */
    public record Service(String id, String name, int price, int durationMin) {
    }

    /** A priced add-on (nail-art tier, removal option or repair). {@code durationMin} is the extra time it adds. */
    public record AddOn(String id, String name, int price, int durationMin) {
    }

    public static final String DEFAULT_ADD_ON = "none";
    // Display names of the two "none" options. Fixed (CatalogValidator rejects renaming them)
    // because booking emails recognise these exact snapshot strings to omit an empty add-on line.
    public static final String NO_NAIL_ART_NAME = "No design (plain colour only)";
    public static final String NO_REMOVAL_NAME = "No removal needed";

    private final long versionId;
    private final CatalogDocument document;
    private final Map<String, Service> services;
    private final Map<String, AddOn> nailArt;
    private final Map<String, AddOn> removal;
    private final Map<String, AddOn> repairs;

    private BookingCatalog(long versionId, CatalogDocument document) {
        this.versionId = versionId;
        this.document = document;
        CatalogDocument visible = document.visibleOnly();
        this.services = index(visible.nailServices(), CatalogDocument.ServiceItem::id,
                item -> new Service(item.id(), item.name(), item.price(), item.durationMin()));
        // Nail-art names are already distinct (Tier 1/2/3), so use them verbatim.
        this.nailArt = index(visible.nailArt(), CatalogDocument.AddOnItem::id,
                item -> new AddOn(item.id(), item.name(), item.price(), item.durationMin()));
        // Removal names collide ("Gel / Hard Gel" by-us vs by-others), so disambiguate with the
        // sub-label for clear admin records. CatalogValidator caps name/sub so this fits the
        // removal VARCHAR(255) snapshot column.
        this.removal = index(visible.removal(), CatalogDocument.AddOnItem::id,
                item -> new AddOn(item.id(), removalName(item), item.price(), item.durationMin()));
        // Repairs are quoted in person: always price 0 / durationMin 0, so they never move a
        // booking's total or occupied interval — only their names are recorded.
        this.repairs = index(visible.repairs(), CatalogDocument.RepairItem::id,
                item -> new AddOn(item.id(), item.name(), 0, 0));
    }

    /** Build a snapshot for a validated document; {@code versionId} is its PUBLISHED row id. */
    public static BookingCatalog of(long versionId, CatalogDocument document) {
        return new BookingCatalog(versionId, document);
    }

    /** The PUBLISHED row id this snapshot was built from — the customer-facing catalog version. */
    public long versionId() {
        return versionId;
    }

    /** The full document, hidden items included. */
    public CatalogDocument document() {
        return document;
    }

    public Optional<Service> service(String id) {
        return Optional.ofNullable(id == null ? null : services.get(id));
    }

    /**
     * Total appointment length in minutes for a quote (service + nail art + removal), or empty if
     * the {@code serviceId} is unknown/missing or either add-on id is unknown. Add-on ids default to
     * {@link #DEFAULT_ADD_ON} ("none", 0 min) when null, so a bare {@code serviceId} is a complete
     * quote. This is the single source of a booking's occupied-interval length — shared by the
     * confirmation quote and the quote-aware availability check.
     */
    public Optional<Integer> totalDurationMin(String serviceId, String nailArtId, String removalId) {
        Optional<Service> service = service(serviceId);
        Optional<AddOn> art = nailArt(nailArtId);
        Optional<AddOn> rem = removal(removalId);
        if (service.isEmpty() || art.isEmpty() || rem.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(service.get().durationMin() + art.get().durationMin() + rem.get().durationMin());
    }

    public Optional<AddOn> nailArt(String id) {
        return Optional.ofNullable(nailArt.get(id == null ? DEFAULT_ADD_ON : id));
    }

    public Optional<AddOn> removal(String id) {
        return Optional.ofNullable(removal.get(id == null ? DEFAULT_ADD_ON : id));
    }

    /**
     * A repair add-on by id, or empty if unknown/hidden. Unlike nail art/removal there is no "none"
     * sentinel — repairs are a multi-select list that is simply empty when nothing is picked, so a
     * null id is never a valid selection here.
     */
    public Optional<AddOn> repair(String id) {
        return Optional.ofNullable(id == null ? null : repairs.get(id));
    }

    /** The name stored on a booking for a removal option ("Gel / Hard Gel — Done by others"). */
    static String removalName(CatalogDocument.AddOnItem item) {
        String sub = item.sub();
        return sub != null && !sub.isBlank() ? item.name() + " — " + sub : item.name();
    }

    private static <T, V> Map<String, V> index(List<T> items, Function<T, String> id, Function<T, V> mapper) {
        Map<String, V> map = new LinkedHashMap<>();
        for (T item : items) {
            map.put(id.apply(item), mapper.apply(item));
        }
        return Map.copyOf(map);
    }
}
