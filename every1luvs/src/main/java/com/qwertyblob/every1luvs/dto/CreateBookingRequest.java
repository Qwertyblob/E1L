package com.qwertyblob.every1luvs.dto;

import java.util.List;

public record CreateBookingRequest(
        Long slotId,
        String customerName,
        String customerEmail,
        String phone,
        String instagram,
        String notes,
        // Legacy display fields are no longer trusted: the server recomputes serviceName,
        // technician, nailArt, removal and totalPrice from the catalog using the *Id fields below.
        String serviceName,
        String technician,
        String nailArt,
        String removal,
        Integer totalPrice,
        // Selection identifiers the server prices against (see BookingCatalog / services.js).
        String serviceId,
        String technicianLevel,
        String nailArtId,
        String removalId,
        // Optional inspo images. Not persisted — forwarded to the salon inbox as email
        // attachments then discarded (see BookingMailService). Validated in BookingService.
        List<BookingAttachment> attachments,
        // Optional multi-select repair add-ons (see BookingCatalog.repair). They are quoted in
        // person, so they carry no price or duration and only their names are recorded.
        List<String> repairIds,
        // The published catalog version (GET /api/catalog "version") of the quote the customer
        // reviewed and accepted. Required: a missing or outdated version is a 409
        // CATALOG_VERSION_MISMATCH so nobody is booked at a price they never saw.
        Long catalogVersion
) {
    public CreateBookingRequest(Long slotId) {
        this(slotId, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // Pre-catalog-version 17-arg signature, kept so existing callers/tests compile unchanged.
    public CreateBookingRequest(
            Long slotId, String customerName, String customerEmail, String phone, String instagram,
            String notes, String serviceName, String technician, String nailArt, String removal,
            Integer totalPrice, String serviceId, String technicianLevel, String nailArtId, String removalId,
            List<BookingAttachment> attachments, List<String> repairIds) {
        this(slotId, customerName, customerEmail, phone, instagram, notes, serviceName, technician,
                nailArt, removal, totalPrice, serviceId, technicianLevel, nailArtId, removalId, attachments,
                repairIds, null);
    }

    public CreateBookingRequest withCatalogVersion(Long version) {
        return new CreateBookingRequest(slotId, customerName, customerEmail, phone, instagram, notes,
                serviceName, technician, nailArt, removal, totalPrice, serviceId, technicianLevel,
                nailArtId, removalId, attachments, repairIds, version);
    }

    // Pre-attachments 15-arg signature, kept so existing callers/tests compile unchanged.
    public CreateBookingRequest(
            Long slotId, String customerName, String customerEmail, String phone, String instagram,
            String notes, String serviceName, String technician, String nailArt, String removal,
            Integer totalPrice, String serviceId, String technicianLevel, String nailArtId, String removalId) {
        this(slotId, customerName, customerEmail, phone, instagram, notes, serviceName, technician,
                nailArt, removal, totalPrice, serviceId, technicianLevel, nailArtId, removalId, null, null, null);
    }

    // Pre-repairs 16-arg signature, kept so existing callers/tests compile unchanged.
    public CreateBookingRequest(
            Long slotId, String customerName, String customerEmail, String phone, String instagram,
            String notes, String serviceName, String technician, String nailArt, String removal,
            Integer totalPrice, String serviceId, String technicianLevel, String nailArtId, String removalId,
            List<BookingAttachment> attachments) {
        this(slotId, customerName, customerEmail, phone, instagram, notes, serviceName, technician,
                nailArt, removal, totalPrice, serviceId, technicianLevel, nailArtId, removalId, attachments, null, null);
    }
}
