package com.qwertyblob.every1luvs.dto;

import com.qwertyblob.every1luvs.entity.SlotEntity;

import java.time.Instant;

public record SlotResponse(
        Long id,
        String title,
        String description,
        Instant startTime,
        Instant endTime,
        int capacity,
        int bookedCount,
        boolean available,
        Instant createdAt
) {
    public static SlotResponse from(SlotEntity slot) {
        return new SlotResponse(
                slot.getId(),
                slot.getTitle(),
                slot.getDescription(),
                slot.getStartTime(),
                slot.getEndTime(),
                slot.getCapacity(),
                slot.getBookedCount(),
                slot.getBookedCount() < slot.getCapacity(),
                slot.getCreatedAt()
        );
    }
}
