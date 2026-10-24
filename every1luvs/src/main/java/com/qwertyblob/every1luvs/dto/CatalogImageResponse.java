package com.qwertyblob.every1luvs.dto;

/** The stored photo's id, to set as a service's {@code imageId} (served at /api/catalog/images/{id}). */
public record CatalogImageResponse(String id) {
}
