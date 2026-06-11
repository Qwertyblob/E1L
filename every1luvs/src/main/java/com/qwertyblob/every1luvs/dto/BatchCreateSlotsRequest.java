package com.qwertyblob.every1luvs.dto;

import java.util.List;

public record BatchCreateSlotsRequest(List<CreateSlotRequest> slots) {
}
