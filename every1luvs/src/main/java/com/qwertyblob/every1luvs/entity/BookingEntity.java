package com.qwertyblob.every1luvs.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "tbl_bookings")
public class BookingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "phone")
    private String phone;

    @Column(name = "instagram")
    private String instagram;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "service_name")
    private String serviceName;

    @Column(name = "technician")
    private String technician;

    @Column(name = "nail_art")
    private String nailArt;

    @Column(name = "removal")
    private String removal;

    @Column(name = "total_price")
    private Integer totalPrice;

    @Column(nullable = false)
    private String status;

    // Soft-archive marker (null = active). Stamped by ArchivalService 1 year after the
    // booking's slot end_time; archived bookings disappear from customer/admin listings
    // (they appear deleted in the frontend) but stay in the table.
    @Column(name = "archived_at")
    private Instant archivedAt;

    // When the ~2-days-before reminder email was sent (null = not yet reminded). Stamped by
    // ReminderService so a booking is reminded at most once, even across re-runs.
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @PrePersist
    void applyDefaults() {
        if (status == null || status.isBlank()) {
            status = "BOOKED";
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSlotId() { return slotId; }
    public void setSlotId(Long slotId) { this.slotId = slotId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getInstagram() { return instagram; }
    public void setInstagram(String instagram) { this.instagram = instagram; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getTechnician() { return technician; }
    public void setTechnician(String technician) { this.technician = technician; }

    public String getNailArt() { return nailArt; }
    public void setNailArt(String nailArt) { this.nailArt = nailArt; }

    public String getRemoval() { return removal; }
    public void setRemoval(String removal) { this.removal = removal; }

    public Integer getTotalPrice() { return totalPrice; }
    public void setTotalPrice(Integer totalPrice) { this.totalPrice = totalPrice; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

    public Instant getReminderSentAt() { return reminderSentAt; }
    public void setReminderSentAt(Instant reminderSentAt) { this.reminderSentAt = reminderSentAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
