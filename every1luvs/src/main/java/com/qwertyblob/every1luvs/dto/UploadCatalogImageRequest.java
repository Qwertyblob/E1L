package com.qwertyblob.every1luvs.dto;

/** A service photo upload from the admin catalog editor: base64 JPEG/PNG bytes (no data: prefix). */
public record UploadCatalogImageRequest(String contentType, String data) {
}
