package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.CatalogDocument;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates + normalizes an admin-edited catalog document (on draft save AND on publish), with
 * hand-rolled 400s in the repo's style. Messages are shown verbatim in the admin editor, so they
 * name the section and item.
 *
 * <p>Bounds exist to keep every booking snapshot inside its column: a booking stores the service
 * name, nail-art name, "removal name — sub" and the comma-joined repair names in VARCHAR(255)
 * columns, so names/subs are capped and the visible repairs are bounded in count and total length.
 */
final class CatalogValidator {

    static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9-]{1,40}$");
    static final int MAX_NAME_LENGTH = 80;
    static final int MAX_LABEL_LENGTH = 80;
    static final int MAX_SUB_LENGTH = 80;
    static final int MAX_DESC_LENGTH = 400;
    static final int MAX_PRICE_NOTE_LENGTH = 30;
    static final int MAX_PRICE = 2_000;
    static final int MAX_DURATION_MIN = 600;
    static final int MAX_ITEMS_PER_SECTION = 40;
    // tbl_bookings.repairs is VARCHAR(255) and holds the selected repair names joined by ", ".
    static final int MAX_SNAPSHOT_LENGTH = 255;
    private static final String REPAIR_SEPARATOR = ", ";

    private CatalogValidator() {
    }

    /**
     * Returns the normalized document (strings trimmed, blanks → null, flags defaulted) or throws a
     * 400. {@code published} is the live catalog (null only when seeding): an id it contains may be
     * hidden but never removed, so a customer mid-checkout always gets a clean "no longer offered"
     * instead of a dangling selection, and the editor can always un-hide it later.
     */
    static CatalogDocument validate(CatalogDocument document, CatalogDocument published) {
        if (document == null) {
            throw bad("The catalog is empty.");
        }
        List<CatalogDocument.ServiceItem> services = normalizeServices(document.nailServices());
        List<CatalogDocument.AddOnItem> nailArt = normalizeAddOns("Nail art", document.nailArt(),
                BookingCatalog.NO_NAIL_ART_NAME);
        List<CatalogDocument.AddOnItem> removal = normalizeAddOns("Removal", document.removal(),
                BookingCatalog.NO_REMOVAL_NAME);
        List<CatalogDocument.RepairItem> repairs = normalizeRepairs(document.repairs());

        if (services.stream().noneMatch(item -> !item.hiddenFromCustomers())) {
            throw bad("Services: at least one service must be visible.");
        }
        for (CatalogDocument.AddOnItem item : removal) {
            // Implied by the name/sub caps today; asserted so a future cap change can't overflow.
            if (BookingCatalog.removalName(item).length() > MAX_SNAPSHOT_LENGTH) {
                throw bad("Removal: \"" + item.name() + "\" — name and short line are too long together.");
            }
        }
        List<CatalogDocument.RepairItem> visibleRepairs = repairs.stream()
                .filter(item -> !item.hiddenFromCustomers()).toList();
        if (visibleRepairs.size() > BookingService.MAX_REPAIRS) {
            throw bad("Repairs: at most " + BookingService.MAX_REPAIRS + " repairs can be visible at once.");
        }
        int joinedLength = visibleRepairs.stream().mapToInt(item -> item.name().length()).sum()
                + Math.max(0, visibleRepairs.size() - 1) * REPAIR_SEPARATOR.length();
        if (joinedLength > MAX_SNAPSHOT_LENGTH) {
            throw bad("Repairs: the visible repair names are too long in total (a booking with every repair "
                    + "must fit " + MAX_SNAPSHOT_LENGTH + " characters). Shorten some names or hide a repair.");
        }

        if (published != null) {
            requireNoRemovals("Services", ids(published.nailServices().stream().map(CatalogDocument.ServiceItem::id)),
                    ids(services.stream().map(CatalogDocument.ServiceItem::id)));
            requireNoRemovals("Nail art", ids(published.nailArt().stream().map(CatalogDocument.AddOnItem::id)),
                    ids(nailArt.stream().map(CatalogDocument.AddOnItem::id)));
            requireNoRemovals("Removal", ids(published.removal().stream().map(CatalogDocument.AddOnItem::id)),
                    ids(removal.stream().map(CatalogDocument.AddOnItem::id)));
            requireNoRemovals("Repairs", ids(published.repairs().stream().map(CatalogDocument.RepairItem::id)),
                    ids(repairs.stream().map(CatalogDocument.RepairItem::id)));
        }
        return new CatalogDocument(services, nailArt, removal, repairs);
    }

    private static List<CatalogDocument.ServiceItem> normalizeServices(List<CatalogDocument.ServiceItem> items) {
        List<CatalogDocument.ServiceItem> raw = requireSection("Services", items);
        Set<String> seen = new HashSet<>();
        List<CatalogDocument.ServiceItem> result = new ArrayList<>();
        for (CatalogDocument.ServiceItem item : raw) {
            String id = requireId("Services", item == null ? null : item.id(), seen);
            String name = requireName("Services", id, item.name());
            String where = "Services: \"" + name + "\"";
            String label = optional(where, "label", item.label(), MAX_LABEL_LENGTH);
            String desc = optional(where, "description", item.desc(), MAX_DESC_LENGTH);
            int price = requirePrice(where, item.price());
            int duration = requireDuration(where, item.durationMin());
            boolean hidden = Boolean.TRUE.equals(item.hidden());
            // V9 (chk duration_min > 0): a bookable service must occupy time.
            if (!hidden && duration <= 0) {
                throw bad(where + " needs a duration of at least 1 minute.");
            }
            result.add(new CatalogDocument.ServiceItem(id, label, name, desc, price, duration,
                    Boolean.TRUE.equals(item.popular()), hidden));
        }
        return List.copyOf(result);
    }

    // Nail art and removal: single-select, so each keeps the free "none" option first. It is what
    // a bare quote defaults to (BookingCatalog.DEFAULT_ADD_ON), so it can't cost, take time or hide,
    // and its name is fixed because booking emails recognise it to omit an empty add-on line.
    private static List<CatalogDocument.AddOnItem> normalizeAddOns(String section, List<CatalogDocument.AddOnItem> items,
                                                                   String noneName) {
        List<CatalogDocument.AddOnItem> raw = requireSection(section, items);
        Set<String> seen = new HashSet<>();
        List<CatalogDocument.AddOnItem> result = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            CatalogDocument.AddOnItem item = raw.get(i);
            String id = requireId(section, item == null ? null : item.id(), seen);
            String name = requireName(section, id, item.name());
            String where = section + ": \"" + name + "\"";
            String sub = optional(where, "short line", item.sub(), MAX_SUB_LENGTH);
            int price = requirePrice(where, item.price());
            int duration = requireDuration(where, item.durationMin());
            boolean hidden = Boolean.TRUE.equals(item.hidden());
            boolean isNone = BookingCatalog.DEFAULT_ADD_ON.equals(id);
            if (i == 0 && !isNone) {
                throw bad(section + ": the first option must be the free \"none\" option.");
            }
            if (isNone && (price != 0 || duration != 0 || hidden || !noneName.equals(name))) {
                throw bad(section + ": the \"" + noneName + "\" option can't be changed.");
            }
            result.add(new CatalogDocument.AddOnItem(id, name, sub, price, duration, hidden));
        }
        return List.copyOf(result);
    }

    private static List<CatalogDocument.RepairItem> normalizeRepairs(List<CatalogDocument.RepairItem> items) {
        List<CatalogDocument.RepairItem> raw = items == null ? List.of() : items;
        if (raw.size() > MAX_ITEMS_PER_SECTION) {
            throw bad("Repairs: too many items (max " + MAX_ITEMS_PER_SECTION + ").");
        }
        Set<String> seen = new HashSet<>();
        List<CatalogDocument.RepairItem> result = new ArrayList<>();
        for (CatalogDocument.RepairItem item : raw) {
            String id = requireId("Repairs", item == null ? null : item.id(), seen);
            String name = requireName("Repairs", id, item.name());
            String where = "Repairs: \"" + name + "\"";
            result.add(new CatalogDocument.RepairItem(id, name,
                    optional(where, "short line", item.sub(), MAX_SUB_LENGTH),
                    optional(where, "description", item.desc(), MAX_DESC_LENGTH),
                    optional(where, "price note", item.priceNote(), MAX_PRICE_NOTE_LENGTH),
                    Boolean.TRUE.equals(item.hidden())));
        }
        return List.copyOf(result);
    }

    private static <T> List<T> requireSection(String section, List<T> items) {
        if (items == null || items.isEmpty()) {
            throw bad(section + ": the section can't be empty.");
        }
        if (items.size() > MAX_ITEMS_PER_SECTION) {
            throw bad(section + ": too many items (max " + MAX_ITEMS_PER_SECTION + ").");
        }
        return items;
    }

    private static String requireId(String section, String id, Set<String> seen) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw bad(section + ": an item has an invalid id. Reload the editor and try again.");
        }
        if (!seen.add(id)) {
            throw bad(section + ": two items share the id \"" + id + "\". Rename one of them.");
        }
        return id;
    }

    private static String requireName(String section, String id, String raw) {
        String name = trimToNull(raw);
        if (name == null) {
            throw bad(section + ": every item needs a name (\"" + id + "\" has none).");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw bad(section + ": \"" + name + "\" — name is too long (max " + MAX_NAME_LENGTH + " characters).");
        }
        return name;
    }

    private static String optional(String where, String field, String raw, int max) {
        String value = trimToNull(raw);
        if (value != null && value.length() > max) {
            throw bad(where + " — " + field + " is too long (max " + max + " characters).");
        }
        return value;
    }

    private static int requirePrice(String where, Integer price) {
        if (price == null || price < 0 || price > MAX_PRICE) {
            throw bad(where + " needs a price between S$0 and S$" + MAX_PRICE + ".");
        }
        return price;
    }

    private static int requireDuration(String where, Integer duration) {
        if (duration == null || duration < 0 || duration > MAX_DURATION_MIN) {
            throw bad(where + " needs a duration between 0 and " + MAX_DURATION_MIN + " minutes.");
        }
        return duration;
    }

    private static void requireNoRemovals(String section, Set<String> publishedIds, Set<String> draftIds) {
        for (String id : publishedIds) {
            if (!draftIds.contains(id)) {
                throw bad(section + ": \"" + id + "\" is live and can't be deleted — hide it instead.");
            }
        }
    }

    private static Set<String> ids(Stream<String> ids) {
        Set<String> set = new HashSet<>();
        ids.forEach(set::add);
        return set;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
