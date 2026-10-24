package com.qwertyblob.every1luvs.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body for every ResponseStatusException. {@code code} is an optional machine-readable
 * reason the SPA branches on (e.g. CATALOG_VERSION_MISMATCH); it is omitted when absent, so
 * uncoded errors serialize exactly as before ({"message": ...}).
 */
public record ErrorResponse(String message, @JsonInclude(JsonInclude.Include.NON_NULL) String code) {

    public ErrorResponse(String message) {
        this(message, null);
    }
}
