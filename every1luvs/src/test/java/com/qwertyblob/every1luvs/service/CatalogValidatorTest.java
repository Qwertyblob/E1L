package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.CatalogDocument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CatalogValidatorTest {

    private final CatalogDocument seed = TestCatalogs.seedDocument();

    @Test
    void seed_isValidAndRoundTripsUnchanged() {
        assertThat(CatalogValidator.validate(seed, seed)).isEqualTo(seed);
    }

    @Test
    void normalizes_trimsStringsBlanksToNullAndDefaultsFlags() {
        CatalogDocument doc = withServices(services -> {
            services.set(0, new CatalogDocument.ServiceItem("express", "  Quick  ", "  Express Manicure ",
                    "   ", 42, 30, null, null));
            return services;
        });

        CatalogDocument.ServiceItem item = CatalogValidator.validate(doc, seed).nailServices().get(0);

        assertThat(item.name()).isEqualTo("Express Manicure");
        assertThat(item.label()).isEqualTo("Quick");
        assertThat(item.desc()).isNull();
        assertThat(item.popular()).isFalse();
        assertThat(item.hidden()).isFalse();
    }

    @Test
    void rejects_missingOrOutOfRangePrice() {
        assertBadRequest(withServices(s -> replaceService(s, 0, null, 30)), "needs a price");
        assertBadRequest(withServices(s -> replaceService(s, 0, -1, 30)), "needs a price");
        assertBadRequest(withServices(s -> replaceService(s, 0, CatalogValidator.MAX_PRICE + 1, 30)), "needs a price");
    }

    @Test
    void rejects_visibleServiceWithoutDuration_butAllowsItWhenHidden() {
        assertBadRequest(withServices(s -> replaceService(s, 0, 42, 0)), "at least 1 minute");

        CatalogDocument hiddenZero = withServices(s -> {
            CatalogDocument.ServiceItem old = s.get(0);
            s.set(0, new CatalogDocument.ServiceItem(old.id(), old.label(), old.name(), old.desc(), 42, 0, false, true));
            return s;
        });
        assertThat(CatalogValidator.validate(hiddenZero, seed).nailServices().get(0).durationMin()).isZero();
    }

    @Test
    void rejects_allServicesHidden() {
        CatalogDocument doc = withServices(s -> {
            s.replaceAll(old -> new CatalogDocument.ServiceItem(old.id(), old.label(), old.name(), old.desc(),
                    old.price(), old.durationMin(), old.popular(), true));
            return s;
        });
        assertBadRequest(doc, "at least one service must be visible");
    }

    @Test
    void rejects_duplicateOrMalformedIds() {
        assertBadRequest(withServices(s -> {
            s.add(s.get(0));
            return s;
        }), "share the id");
        assertBadRequest(withServices(s -> {
            CatalogDocument.ServiceItem old = s.get(0);
            s.set(0, new CatalogDocument.ServiceItem("Bad Id!", old.label(), old.name(), old.desc(),
                    old.price(), old.durationMin(), false, false));
            return s;
        }), "invalid id");
    }

    @Test
    void rejects_overlongName() {
        String longName = "x".repeat(CatalogValidator.MAX_NAME_LENGTH + 1);
        assertBadRequest(withServices(s -> {
            CatalogDocument.ServiceItem old = s.get(0);
            s.set(0, new CatalogDocument.ServiceItem(old.id(), old.label(), longName, old.desc(),
                    old.price(), old.durationMin(), false, false));
            return s;
        }), "name is too long");
    }

    @Test
    void rejects_removingALiveItem_butAllowsHidingIt() {
        CatalogDocument removed = withServices(s -> {
            s.remove(0);
            return s;
        });
        assertBadRequest(removed, "can't be deleted — hide it instead");

        CatalogDocument hidden = withServices(s -> {
            CatalogDocument.ServiceItem old = s.get(0);
            s.set(0, new CatalogDocument.ServiceItem(old.id(), old.label(), old.name(), old.desc(),
                    old.price(), old.durationMin(), false, true));
            return s;
        });
        assertThat(CatalogValidator.validate(hidden, seed).nailServices().get(0).hidden()).isTrue();
    }

    @Test
    void allows_addingANewItem() {
        CatalogDocument added = withServices(s -> {
            s.add(new CatalogDocument.ServiceItem("gel-pedicure", null, "Gel Pedicure", null, 65, 60, false, false));
            return s;
        });
        assertThat(CatalogValidator.validate(added, seed).nailServices()).hasSize(seed.nailServices().size() + 1);
    }

    @Test
    void noneOption_mustStayFirstFreeVisibleAndKeepItsName() {
        List<CatalogDocument.AddOnItem> moved = new ArrayList<>(seed.nailArt());
        moved.add(moved.remove(0));
        assertBadRequest(new CatalogDocument(seed.nailServices(), moved, seed.removal(), seed.repairs()),
                "first option must be the free");

        assertBadRequest(withNone(new CatalogDocument.AddOnItem("none", BookingCatalog.NO_NAIL_ART_NAME, null, 5, 0, false)),
                "can't be changed");
        assertBadRequest(withNone(new CatalogDocument.AddOnItem("none", BookingCatalog.NO_NAIL_ART_NAME, null, 0, 0, true)),
                "can't be changed");
        // Booking emails recognise the exact "none" name to omit an empty add-on line.
        assertBadRequest(withNone(new CatalogDocument.AddOnItem("none", "Plain please", null, 0, 0, false)),
                "can't be changed");
    }

    @Test
    void repairs_visibleCountIsCappedAtTheBookingSelectionLimit() {
        List<CatalogDocument.RepairItem> repairs = new ArrayList<>();
        for (int i = 0; i < BookingService.MAX_REPAIRS + 1; i++) {
            repairs.add(new CatalogDocument.RepairItem("r" + i, "R" + i, null, null, null, false));
        }
        assertBadRequest(new CatalogDocument(seed.nailServices(), seed.nailArt(), seed.removal(), repairs),
                "at most " + BookingService.MAX_REPAIRS);

        // Hidden repairs don't count: the cap is about what a customer can select at once.
        repairs.set(0, new CatalogDocument.RepairItem("r0", "R0", null, null, null, true));
        CatalogDocument ok = new CatalogDocument(seed.nailServices(), seed.nailArt(), seed.removal(), repairs);
        assertThat(CatalogValidator.validate(ok, null).repairs()).hasSize(BookingService.MAX_REPAIRS + 1);
    }

    @Test
    void repairs_joinedVisibleNamesMustFitTheBookingColumn() {
        // 4 × 70 chars + 3 × ", " = 286 > 255: a booking selecting all of them would overflow.
        List<CatalogDocument.RepairItem> repairs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            repairs.add(new CatalogDocument.RepairItem("r" + i, String.valueOf((char) ('a' + i)).repeat(70),
                    null, null, null, false));
        }
        assertBadRequest(new CatalogDocument(seed.nailServices(), seed.nailArt(), seed.removal(), repairs),
                "too long in total");
    }

    @Test
    void repairs_priceNoteIsBounded() {
        List<CatalogDocument.RepairItem> repairs = List.of(new CatalogDocument.RepairItem("nail-fix", "Nail Fix",
                null, null, "x".repeat(CatalogValidator.MAX_PRICE_NOTE_LENGTH + 1), false),
                seed.repairs().get(1));
        assertBadRequest(new CatalogDocument(seed.nailServices(), seed.nailArt(), seed.removal(), repairs),
                "price note is too long");
    }

    private CatalogDocument withServices(UnaryOperator<List<CatalogDocument.ServiceItem>> edit) {
        List<CatalogDocument.ServiceItem> services = edit.apply(new ArrayList<>(seed.nailServices()));
        return new CatalogDocument(services, seed.nailArt(), seed.removal(), seed.repairs());
    }

    private CatalogDocument withNone(CatalogDocument.AddOnItem none) {
        List<CatalogDocument.AddOnItem> nailArt = new ArrayList<>(seed.nailArt());
        nailArt.set(0, none);
        return new CatalogDocument(seed.nailServices(), nailArt, seed.removal(), seed.repairs());
    }

    private static List<CatalogDocument.ServiceItem> replaceService(List<CatalogDocument.ServiceItem> services,
                                                                  int index, Integer price, Integer duration) {
        CatalogDocument.ServiceItem old = services.get(index);
        services.set(index, new CatalogDocument.ServiceItem(old.id(), old.label(), old.name(), old.desc(),
                price, duration, old.popular(), old.hidden()));
        return services;
    }

    private void assertBadRequest(CatalogDocument doc, String messageFragment) {
        ResponseStatusException ex = catchThrowableOfType(() -> CatalogValidator.validate(doc, seed),
                ResponseStatusException.class);
        assertThat(ex).as("expected a 400 containing \"%s\"", messageFragment).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getReason()).contains(messageFragment);
    }
}
