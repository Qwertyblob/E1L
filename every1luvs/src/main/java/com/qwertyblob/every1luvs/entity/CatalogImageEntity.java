package com.qwertyblob.every1luvs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

/**
 * A service photo (see {@code V14__catalog_images.sql}). Immutable once stored: uploads are
 * content-addressed, so the bytes behind an id never change and can be cached indefinitely.
 */
@Entity
@Table(name = "tbl_catalog_images")
public class CatalogImageEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(nullable = false)
    private byte[] data;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
