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
        // Joined display names of any selected repairs (e.g. "Nail Fix, Single Nail Extension"),
        // or null if none. Informational only — never folded into totalPrice.
        String repairs,
        Integer totalPrice,
        String status,
        Instant confirmedAt,
        Instant createdAt
) {
}
