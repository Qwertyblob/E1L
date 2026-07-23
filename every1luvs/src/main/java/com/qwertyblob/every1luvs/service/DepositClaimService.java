package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.DepositClaimRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Handles a customer's "I paid the deposit but the booking failed" report: validates and bounds the
 * public input, rate-limits it per IP (the endpoint emails the salon, so it must not be a spam
 * vector), then hands it to {@link BookingMailService} for a best-effort admin notification. There
 * is no persisted claim record — this manual, no-gateway deposit flow relies on the salon following
 * up from the email with the customer's payment receipt.
 */
@Service
public class DepositClaimService {

    // A report emails the salon inbox, so cap it per client IP to bound a public flood.
    private static final int MAX_CLAIMS_PER_IP = 3;
    private static final long CLAIM_WINDOW_MS = 60 * 60 * 1_000L;
    private static final int MAX_FIELD_LENGTH = 255;
    private static final int MAX_REASON_LENGTH = 500;

    private final RateLimiterService rateLimiter;
    private final BookingMailService bookingMailService;

    public DepositClaimService(RateLimiterService rateLimiter, BookingMailService bookingMailService) {
        this.rateLimiter = rateLimiter;
        this.bookingMailService = bookingMailService;
    }

    public void submit(DepositClaimRequest request, String clientIp) {
        if (request == null || isBlank(request.customerName()) || isBlank(request.customerEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and email are required.");
        }
        if (clientIp != null && !clientIp.isBlank()) {
            rateLimiter.check("deposit-claim:ip:" + clientIp, MAX_CLAIMS_PER_IP, CLAIM_WINDOW_MS,
                    "Too many requests. Please contact us directly with your payment receipt.");
        }
        // Bound every field before it reaches the inbox so an oversized public body can't store junk.
        DepositClaimRequest bounded = new DepositClaimRequest(
                trim(request.customerName(), MAX_FIELD_LENGTH),
                trim(request.customerEmail(), MAX_FIELD_LENGTH),
                trim(request.phone(), MAX_FIELD_LENGTH),
                trim(request.serviceName(), MAX_FIELD_LENGTH),
                trim(request.appointmentDate(), MAX_FIELD_LENGTH),
                trim(request.appointmentTime(), MAX_FIELD_LENGTH),
                trim(request.reason(), MAX_REASON_LENGTH));
        bookingMailService.sendDepositClaimNotification(bounded);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }
}
