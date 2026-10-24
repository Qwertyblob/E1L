package com.qwertyblob.every1luvs.dto;

import java.time.Instant;

/**
 * The admin editor's view: the live catalog plus the working draft (null when there is none).
 * The editor echoes {@code draft.id}/{@code draft.version} on every save/discard/publish, and
 * {@code published.id} as {@code basePublishedId} when it creates a draft.
 */
public record AdminCatalogResponse(Published published, Draft draft) {

    public record Published(long id, CatalogDocument content, Instant publishedAt, String publishedBy) {
    }

    public record Draft(long id, long version, Long basePublishedId, CatalogDocument content,
                        Instant updatedAt, String updatedBy) {
    }
}
