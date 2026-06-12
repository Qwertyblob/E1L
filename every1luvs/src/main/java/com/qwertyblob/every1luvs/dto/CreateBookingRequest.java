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
        String removalId,
        // Guest bookings only: the emailed verification code proving inbox control
        // (see GuestVerificationService). Ignored for authenticated bookings.
        String otp
) {
    public CreateBookingRequest(Long slotId) {
        this(slotId, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    // Pre-OTP shape, kept so existing callers/tests without a code still compile.
    public CreateBookingRequest(Long slotId, String customerName, String customerEmail, String phone,
                                String instagram, String notes, String serviceName, String technician,
                                String nailArt, String removal, Integer totalPrice, String serviceId,
                                String technicianLevel, String nailArtId, String removalId) {
        this(slotId, customerName, customerEmail, phone, instagram, notes, serviceName, technician,
                nailArt, removal, totalPrice, serviceId, technicianLevel, nailArtId, removalId, null);
    }
}
