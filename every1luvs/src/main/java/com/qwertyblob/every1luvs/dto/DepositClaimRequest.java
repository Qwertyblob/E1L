package com.qwertyblob.every1luvs.dto;

/**
 * A customer's report that they paid the deposit but their booking did not complete. Sent
 * best-effort by the client when {@code POST /api/bookings} fails after the deposit was marked
 * paid, so the salon can follow up to reschedule or refund. Nothing here is trusted for pricing or
 * scheduling — it is relayed to the admin inbox for a manual follow-up only.
 */
public record DepositClaimRequest(
        String customerName,
        String customerEmail,
        String phone,
        String serviceName,
        String appointmentDate,
        String appointmentTime,
        // The booking-failure message the customer saw, for context.
        String reason
) {
}
