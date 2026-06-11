package com.qwertyblob.every1luvs.dto;

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
        String removalId
) {
    public CreateBookingRequest(Long slotId) {
        this(slotId, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
