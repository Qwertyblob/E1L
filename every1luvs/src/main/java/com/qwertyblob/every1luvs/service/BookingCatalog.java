package com.qwertyblob.every1luvs.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side catalog of bookable services and add-ons. It is the authoritative source
 * for prices and display names so the backend can recompute a booking's total instead
 * of trusting client-supplied values.
 *
 * <p>The data is loaded from {@code /catalog/services.json} on the classpath, which the
 * Maven build copies verbatim from {@code client/src/services.json} — the single canonical
 * source the booking UI also renders from. Edit prices/options in that one file.
 */
public final class BookingCatalog {

    private static final String CATALOG_RESOURCE = "/catalog/services.json";

    /**
     * A bookable service with its price (SGD, whole dollars).
     */
    public record Service(String id, String name, int price, int durationMin) {
    }

    /** A priced add-on (nail-art tier or removal option). {@code durationMin} is the extra time it adds. */
    public record AddOn(String id, String name, int price, int durationMin) {
    }

    public static final String DEFAULT_ADD_ON = "none";

    // Raw shapes of the shared JSON. Presentation-only fields (label, desc, duration, time,
    // popular, …) are intentionally ignored here — the backend needs id/name/price plus the
    // numeric durationMin (used to surface how long a service/add-on takes).
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawService(String id, String name, int price, int durationMin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawAddOn(String id, String name, String sub, int price, int durationMin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CatalogData(List<RawService> NAIL_SERVICES,
                               List<RawAddOn> NAIL_ART, List<RawAddOn> REMOVAL) {
    }

    private static final Map<String, Service> SERVICES;
    private static final Map<String, AddOn> NAIL_ART;
    private static final Map<String, AddOn> REMOVAL;

    static {
        CatalogData data = loadCatalog();
        SERVICES = services(data.NAIL_SERVICES());
        // Nail-art names are already distinct (Tier 1/2/3), so use them verbatim.
        NAIL_ART = addOns(data.NAIL_ART(), false);
        // Removal names collide ("Gel / Hard Gel" by-us vs by-others), so disambiguate with
        // the sub-label for clear admin records.
        REMOVAL = addOns(data.REMOVAL(), true);
    }

    private BookingCatalog() {
    }

    public static Optional<Service> service(String id) {
        return Optional.ofNullable(id == null ? null : SERVICES.get(id));
    }

    /**
     * Total appointment length in minutes for a quote (service + nail art + removal), or empty if
     * the {@code serviceId} is unknown/missing or either add-on id is unknown. Add-on ids default to
     * {@link #DEFAULT_ADD_ON} ("none", 0 min) when null, so a bare {@code serviceId} is a complete
     * quote. This is the single source of a booking's occupied-interval length — shared by the
     * confirmation quote and the quote-aware availability check.
     */
    public static Optional<Integer> totalDurationMin(String serviceId, String nailArtId, String removalId) {
        Optional<Service> service = service(serviceId);
        Optional<AddOn> nailArt = nailArt(nailArtId);
        Optional<AddOn> removal = removal(removalId);
        if (service.isEmpty() || nailArt.isEmpty() || removal.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(service.get().durationMin() + nailArt.get().durationMin() + removal.get().durationMin());
    }

    public static Optional<AddOn> nailArt(String id) {
        return Optional.ofNullable(NAIL_ART.get(id == null ? DEFAULT_ADD_ON : id));
    }

    public static Optional<AddOn> removal(String id) {
        return Optional.ofNullable(REMOVAL.get(id == null ? DEFAULT_ADD_ON : id));
    }

    private static CatalogData loadCatalog() {
        try (InputStream in = BookingCatalog.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Booking catalog resource not found: " + CATALOG_RESOURCE
                        + " (is the Maven copy-shared-catalog step wired up?)");
            }
            return JsonMapper.builder().build().readValue(in, CatalogData.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load booking catalog from " + CATALOG_RESOURCE, e);
        }
    }

    private static Map<String, Service> services(List<RawService> nailServices) {
        Map<String, Service> map = new LinkedHashMap<>();
        if (nailServices != null) {
            for (RawService raw : nailServices) {
                map.put(raw.id(), new Service(raw.id(), raw.name(), raw.price(), raw.durationMin()));
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, AddOn> addOns(List<RawAddOn> items, boolean disambiguateWithSub) {
        Map<String, AddOn> map = new LinkedHashMap<>();
        if (items != null) {
            for (RawAddOn raw : items) {
                String name = raw.name();
                if (disambiguateWithSub && raw.sub() != null && !raw.sub().isBlank()) {
                    name = name + " — " + raw.sub();
                }
                map.put(raw.id(), new AddOn(raw.id(), name, raw.price(), raw.durationMin()));
            }
        }
        return Map.copyOf(map);
    }
}
