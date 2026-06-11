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
        Integer totalPrice,
        String status,
        Instant createdAt
) {
}
