package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    // "none" add-on display names (from services.json) — skip these lines so a plain/CoolSculpting
    // booking doesn't show empty add-ons.
    private static final String NO_NAIL_ART = "No design (plain colour only)";
    private static final String NO_REMOVAL = "No removal needed";

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public BookingMailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
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

    private String buildBody(BookingResponse booking) {
        StringBuilder body = new StringBuilder();
        body.append("Hi ").append(booking.userName()).append(",\n\n");
        body.append("Your every1luvs booking is confirmed. Here are the details:\n\n");
        body.append("Booking reference: #").append(booking.id()).append('\n');
        body.append("Service: ").append(booking.serviceName());
        if (booking.technician() != null && !booking.technician().isBlank()) {
            body.append(" (").append(booking.technician()).append(')');
        }
        body.append('\n');
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
