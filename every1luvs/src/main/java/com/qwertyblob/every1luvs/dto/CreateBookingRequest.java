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
        List<BookingAttachment> attachments
) {
    public CreateBookingRequest(Long slotId) {
        this(slotId, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // Pre-attachments 15-arg signature, kept so existing callers/tests compile unchanged.
    public CreateBookingRequest(
            Long slotId, String customerName, String customerEmail, String phone, String instagram,
            String notes, String serviceName, String technician, String nailArt, String removal,
            Integer totalPrice, String serviceId, String technicianLevel, String nailArtId, String removalId) {
        this(slotId, customerName, customerEmail, phone, instagram, notes, serviceName, technician,
                nailArt, removal, totalPrice, serviceId, technicianLevel, nailArtId, removalId, null);
    }
}
