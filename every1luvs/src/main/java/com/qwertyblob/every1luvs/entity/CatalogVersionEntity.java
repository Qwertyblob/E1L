package com.qwertyblob.every1luvs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * One full catalog snapshot (see {@code V13__catalog_versions.sql}). Exactly one row is
 * {@link #PUBLISHED} (the live catalog; its id is the customer-facing catalog version), at most one
 * is the admin's {@link #DRAFT}, and every previously live row is kept as {@link #SUPERSEDED}.
 */
@Entity
@Table(name = "tbl_catalog_versions")
public class CatalogVersionEntity {

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String SUPERSEDED = "SUPERSEDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String status;

    // The CatalogDocument as JSON.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // Optimistic-lock counter; the admin editor echoes it back so a save/discard/publish only
    // applies to the exact draft revision the admin was looking at.
    @Version
    private Long version;

    @Column(name = "base_published_id")
    private Long basePublishedId;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private String publishedBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Long getBasePublishedId() { return basePublishedId; }
    public void setBasePublishedId(Long basePublishedId) { this.basePublishedId = basePublishedId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }

    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String publishedBy) { this.publishedBy = publishedBy; }
}
