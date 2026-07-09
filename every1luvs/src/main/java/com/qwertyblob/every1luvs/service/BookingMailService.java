package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import com.qwertyblob.every1luvs.dto.BookingResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Sends best-effort booking-confirmation emails. Runs {@code @Async} (off the request thread) and
 * its failures are swallowed/logged by Spring's default async handler, so a mail problem can never
 * turn a successful booking into an error. Mirrors {@link OtpDeliveryService}'s plain-text style.
 */
@Service
public class BookingMailService {
    private static final Logger logger = LoggerFactory.getLogger(BookingMailService.class);

    // Slot instants are stored as UTC wall-clock (see SlotService), so format in UTC to echo the
    // exact time the admin entered. Locale is pinned so day/month names are deterministic.
    private static final DateTimeFormatter SLOT_FORMAT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    // "none" add-on display names (from services.json) — skip these lines so a plain
    // booking doesn't show empty add-ons.
    private static final String NO_NAIL_ART = "No design (plain colour only)";
    private static final String NO_REMOVAL = "No removal needed";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    // Inbox that receives the full booking notification (all details + inspo photos) on every
    // booking — the bootstrap admin's email. Falls back to the from address if it is unset so the
    // notification always lands somewhere readable.
    private final String adminAddress;

    public BookingMailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.auth.bootstrap-admin-email:}") String adminAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.adminAddress = (adminAddress == null || adminAddress.isBlank()) ? fromAddress : adminAddress;
    }

    @Async
    public void sendBookingConfirmation(BookingResponse booking) {
        if (booking == null || booking.customerEmail() == null || booking.customerEmail().isBlank()) {
            return; // nothing to send to
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(booking.customerEmail());
        message.setSubject("Your every1luvs booking is confirmed");
        message.setText(buildBody(booking));

        mailSender.send(message);
        logger.info("Booking confirmation email sent to {} (booking #{})",
                booking.customerEmail(), booking.id());
    }

    /**
     * Emails the admin a full copy of every booking — all the details shown in the admin view plus
     * the customer's inspo photos as attachments. Best-effort and {@code @Async} like the customer
     * confirmation, so a mail failure never affects the booking. Photos are attached when present
     * but are never required, and are not stored anywhere else.
     */
    @Async
    public void sendAdminBookingNotification(BookingResponse booking, List<BookingAttachment> attachments) {
        if (booking == null) {
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(adminAddress);

            int attached = 0;
            List<BookingAttachment> images = attachments == null ? List.of() : attachments;
            for (int i = 0; i < images.size(); i++) {
                BookingAttachment attachment = images.get(i);
                if (attachment == null || attachment.data() == null || attachment.data().isBlank()) {
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = Base64.getDecoder().decode(attachment.data());
                } catch (IllegalArgumentException e) {
                    logger.warn("Skipping malformed inspo image on booking #{}", booking.id());
                    continue;
                }
                String filename = safeFilename(attachment.filename(), i);
                String contentType = (attachment.contentType() == null || attachment.contentType().isBlank())
                        ? "application/octet-stream" : attachment.contentType();
                helper.addAttachment(filename, new ByteArrayResource(bytes), contentType);
                attached++;
            }

            helper.setSubject("New booking #" + booking.id() + " — " + safe(booking.userName()));
            helper.setText(buildAdminBody(booking, attached));

            mailSender.send(message);
            logger.info("Admin booking notification sent to {} with {} image(s) (booking #{})",
                    adminAddress, attached, booking.id());
        } catch (MessagingException e) {
            // Swallow: the customer's booking already succeeded regardless of this notification.
            logger.warn("Failed to send admin booking notification for booking #{}: {}",
                    booking.id(), e.getMessage());
        }
    }

    // Mirrors the fields the admin sees for a booking in the web app (no technician — that tier was
    // removed). Add-on lines are always shown so the admin gets the complete record at a glance.
    private String buildAdminBody(BookingResponse booking, int imageCount) {
        StringBuilder body = new StringBuilder();
        body.append("A new appointment has been booked.\n\n");
        body.append("Booking reference: #").append(booking.id()).append('\n');
        body.append("Status: ").append(safe(booking.status())).append('\n');
        body.append("Customer: ").append(safe(booking.userName()));
        if (booking.customerEmail() != null && !booking.customerEmail().isBlank()) {
            body.append(" <").append(booking.customerEmail()).append('>');
        }
        body.append('\n');
        if (booking.phone() != null && !booking.phone().isBlank()) {
            body.append("Phone: ").append(booking.phone()).append('\n');
        }
        if (booking.instagram() != null && !booking.instagram().isBlank()) {
            body.append("Instagram: ").append(booking.instagram()).append('\n');
        }
        body.append("Service: ").append(safe(booking.serviceName())).append('\n');
        body.append("Nail art: ").append(safe(booking.nailArt())).append('\n');
        body.append("Removal: ").append(safe(booking.removal())).append('\n');
        body.append("When: ")
                .append(SLOT_FORMAT.format(booking.slotStartTime()))
                .append(" – ")
                .append(SLOT_FORMAT.format(booking.slotEndTime()))
                .append('\n');
        body.append("Total: S$").append(booking.totalPrice()).append('\n');
        body.append("Notes: ").append(
                (booking.notes() == null || booking.notes().isBlank()) ? "—" : booking.notes()).append('\n');
        body.append("Inspo photos attached: ").append(imageCount).append('\n');
        return body.toString();
    }

    // Attachment filenames come from the client; fall back to a safe default and strip any path
    // separators so a crafted name can't influence where the mail library writes.
    private String safeFilename(String filename, int index) {
        if (filename == null || filename.isBlank()) {
            return "inspo-" + (index + 1) + ".jpg";
        }
        String cleaned = filename.replaceAll("[\\\\/\\r\\n]", "_").trim();
        return cleaned.isEmpty() ? ("inspo-" + (index + 1) + ".jpg") : cleaned;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String buildBody(BookingResponse booking) {
        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(booking.userName()).append(",\n\n");
        body.append("Your every1luvs booking is confirmed. Here are the details:\n\n");
        body.append("Booking reference: #").append(booking.id()).append('\n');
        body.append("Service: ").append(booking.serviceName()).append('\n');
        if (isMeaningful(booking.nailArt(), NO_NAIL_ART)) {
            body.append("Nail art: ").append(booking.nailArt()).append('\n');
        }
        if (isMeaningful(booking.removal(), NO_REMOVAL)) {
            body.append("Removal: ").append(booking.removal()).append('\n');
        }
        body.append("When: ")
                .append(SLOT_FORMAT.format(booking.slotStartTime()))
                .append(" – ")
                .append(SLOT_FORMAT.format(booking.slotEndTime()))
                .append('\n');
        body.append("Total: S$").append(booking.totalPrice()).append("\n\n");
        body.append("Need to make a change or cancel? Just reply to this email or get in touch and we'll help.\n\n");
        body.append("See you soon!\nevery1luvs\n");
        return body.toString();
    }

    private boolean isMeaningful(String value, String defaultName) {
        return value != null && !value.isBlank() && !value.equals(defaultName);
    }
}
