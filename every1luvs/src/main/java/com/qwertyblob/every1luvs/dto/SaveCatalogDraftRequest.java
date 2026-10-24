package com.qwertyblob.every1luvs.dto;

/**
 * Save the admin's catalog draft. {@code draftId == null} creates the draft and then
 * {@code basePublishedId} — the live catalog id the admin started editing from — is required, so
 * edits made against an older live catalog can't be silently re-based onto a newer one. Otherwise
 * {@code draftId}/{@code version} must match the current draft exactly (else 409).
 */
public record SaveCatalogDraftRequest(Long draftId, Long version, Long basePublishedId, CatalogDocument content) {
}
