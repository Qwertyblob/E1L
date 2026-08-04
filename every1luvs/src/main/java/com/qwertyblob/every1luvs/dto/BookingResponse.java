package com.qwertyblob.every1luvs.dto;

import java.time.Instant;

public record BookingResponse(
        Long id,
        Long slotId,
        String slotTitle,
        Instant slotStartTime,
        Instant slotEndTime,
        Long userId,
        String userName,
        String customerEmail,
        String phone,
        String instagram,
        String notes,
        String serviceName,
        String technician,
        String nailArt,
        String removal,
        // Comma-joined repair add-on names, or null when none were selected. Repairs are quoted
        // in person, so they are recorded for the salon but excluded from totalPrice.
        String repairs,
        Integer totalPrice,
        String status,
        Instant confirmedAt,
        Instant createdAt
) {
}
