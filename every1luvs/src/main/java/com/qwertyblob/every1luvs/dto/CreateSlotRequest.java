package com.qwertyblob.every1luvs.dto;

public record CreateSlotRequest(String title, String description, String startTime, String endTime, Integer capacity) {
}
