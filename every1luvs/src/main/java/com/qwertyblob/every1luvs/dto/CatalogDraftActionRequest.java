package com.qwertyblob.every1luvs.dto;

/**
 * Discard or publish the catalog draft. Both ids are required and must match the current draft
 * exactly, so an admin only ever publishes/discards the revision they actually reviewed.
 */
public record CatalogDraftActionRequest(Long draftId, Long version) {
}
