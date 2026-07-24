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

    // Total appointment length in minutes (service + nail art + removal), computed server-side
    // from BookingCatalog at creation. Defines the booking's occupied interval
    // [slotStart, slotStart + durationMin), which SchedulingGuard uses to enforce the
    // no-overlapping-appointment invariant. NULL = legacy row booked before this feature, which
    // falls back to occupying only its own slot's [start, end). V9 forbids non-positive values.
    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(nullable = false)
    private String status;

    // When the admin confirmed this booking (null = pending, awaiting deposit verification).
    // Set by BookingService.adminConfirmBooking once the deposit is seen; only then does the
    // client confirmation email go out. Capacity is still governed by status = 'BOOKED', so a
    // pending booking already holds its seat — this flag only gates the email + the admin's
    // Pending vs Upcoming split.
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    // Soft-archive marker (null = active). Stamped by ArchivalService 1 year after the
    // booking's slot end_time; archived bookings disappear from customer/admin listings
    // (they appear deleted in the frontend) but stay in the table.
    @Column(name = "archived_at")
    private Instant archivedAt;

    // When the ~2-days-before reminder email was sent (null = not yet reminded). Stamped by
    // ReminderService so a booking is reminded at most once, even across re-runs.
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    // When the post-appointment review-request email was sent (null = not yet asked). Stamped by
    // ReviewRequestService, and only ever for COMPLETED bookings, so a booking is asked at most once.
    @Column(name = "review_sent_at")
    private Instant reviewSentAt;

    // When the ~3-weeks-after rebooking-prompt email was sent (null = not yet nudged). Stamped by
    // RebookingService, and only ever for COMPLETED bookings, so a booking is nudged at most once.
    @Column(name = "rebooking_sent_at")
    private Instant rebookingSentAt;

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

    public Integer getDurationMin() { return durationMin; }
    public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public Instant getArchivedAt() { return archivedAt; }
    public void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

    public Instant getReminderSentAt() { return reminderSentAt; }
    public void setReminderSentAt(Instant reminderSentAt) { this.reminderSentAt = reminderSentAt; }

    public Instant getReviewSentAt() { return reviewSentAt; }
    public void setReviewSentAt(Instant reviewSentAt) { this.reviewSentAt = reviewSentAt; }

    public Instant getRebookingSentAt() { return rebookingSentAt; }
    public void setRebookingSentAt(Instant rebookingSentAt) { this.rebookingSentAt = rebookingSentAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
