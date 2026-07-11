package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import com.qwertyblob.every1luvs.dto.BookingResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
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
    // Customer confirmation lists date and start time on separate lines (no end time), so split the
    // slot's UTC wall-clock into its own date and time formatters.
    private static final DateTimeFormatter SLOT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SLOT_TIME_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    // Flat deposit that secures a slot, mirrored from the client (BookingModal). Applied to the
    // final bill; the recomputed booking total is the estimate.
    private static final int DEPOSIT_SGD = 30;

    // "none" add-on display names (from services.json) — skip these lines so a plain
    // booking doesn't show empty add-ons.
    private static final String NO_NAIL_ART = "No design (plain colour only)";
    private static final String NO_REMOVAL = "No removal needed";

    // Studio arrival details, shown only in the pre-appointment reminder (kept out of the
    // confirmation, whose copy promises the address in a separate email nearer the day).
    private static final String STUDIO_ADDRESS = "Block 190 Lorong 6 Toa Payoh";
    private static final String STUDIO_TRANSPORT =
            "Toa Payoh HDB Hub Car Park / Toa Payoh MRT Station";
    private static final String INSTAGRAM_HANDLE = "@every1luvsnails";
    private static final String INSTAGRAM_URL = "https://instagram.com/every1luvsnails";

    private final JavaMailSender mailSender;
    private final String fromAddress;
    // Inbox that receives the full booking notification (all details + inspo photos) on every
    // booking — the bootstrap admin's email. Falls back to the from address if it is unset so the
    // notification always lands somewhere readable.
    private final String adminAddress;
    // Destination for the post-appointment review request (e.g. a Google Business "write a review"
    // link). Blank until configured; while blank the review-request feature is dormant — nothing is
    // sent and no booking is stamped, so it can be switched on later without missing recent clients.
    private final String reviewUrl;

    public BookingMailService(
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String fromAddress,
            @Value("${app.auth.bootstrap-admin-email:}") String adminAddress,
            @Value("${app.review.url:}") String reviewUrl
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.adminAddress = (adminAddress == null || adminAddress.isBlank()) ? fromAddress : adminAddress;
        this.reviewUrl = reviewUrl == null ? "" : reviewUrl.trim();
    }

    /** Whether review requests can be sent — false until a review URL is configured (feature dormant). */
    public boolean isReviewRequestEnabled() {
        return !reviewUrl.isBlank();
    }

    @Async
    public void sendBookingConfirmation(BookingResponse booking) {
        if (booking == null || booking.customerEmail() == null || booking.customerEmail().isBlank()) {
            return; // nothing to send to
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(booking.customerEmail());
        message.setSubject("Every1Luvs appointment confirmation");
        message.setText(buildBody(booking));

        mailSender.send(message);
        logger.info("Booking confirmation email sent to {} (booking #{})",
                booking.customerEmail(), booking.id());
    }

    /**
     * Sends the pre-appointment reminder (~2 days before) with the studio address and arrival
     * instructions. Sent as HTML so the Instagram handle is a clickable link.
     *
     * <p>Runs synchronously (unlike the confirmation) so the caller learns whether the mail
     * actually left: {@link ReminderService} only stamps {@code reminder_sent_at} when this
     * returns {@code true}, giving at-least-once delivery — a transient SMTP failure returns
     * {@code false} and the booking is retried on the next sweep.
     *
     * @return {@code true} if the reminder was sent, or if there is no address to send to (so the
     *     booking is settled and won't be reprocessed forever); {@code false} on a send failure
     *     that should be retried.
     */
    public boolean sendBookingReminder(BookingResponse booking) {
        if (booking == null || booking.customerEmail() == null || booking.customerEmail().isBlank()) {
            return true; // nothing to send to — settled, don't keep reprocessing it
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(booking.customerEmail());
            helper.setSubject("See you soon — your Every1Luvs appointment reminder");
            helper.setText(buildReminderHtml(booking), true);

            mailSender.send(message);
            logger.info("Booking reminder email sent to {} (booking #{})",
                    booking.customerEmail(), booking.id());
            return true;
        } catch (MessagingException | MailException e) {
            // Don't stamp: leave reminder_sent_at null so the next sweep retries this booking.
            logger.warn("Failed to send booking reminder for booking #{}, will retry next sweep: {}",
                    booking.id(), e.getMessage());
            return false;
        }
    }

    /**
     * Sends the post-appointment review request. Only ever called for COMPLETED bookings (see
     * {@link ReviewRequestService}), so no-shows are never asked. Synchronous and HTML (so the
     * review link is clickable), returning whether the caller should stamp {@code review_sent_at}:
     * the immediate on-complete path and the fallback sweep both stamp only on {@code true}, giving
     * at-least-once delivery capped at one request per booking.
     *
     * @return {@code true} if the request was sent, or if there is no address to send to;
     *     {@code false} if the feature is disabled (no review URL configured — nothing is stamped,
     *     so it sends once enabled) or a transient send failure occurred (retried next sweep).
     */
    public boolean sendReviewRequest(BookingResponse booking) {
        if (!isReviewRequestEnabled()) {
            return false; // dormant until a review URL is configured — don't send, don't stamp
        }
        if (booking == null || booking.customerEmail() == null || booking.customerEmail().isBlank()) {
            return true; // nothing to send to — settled, don't keep reprocessing it
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(booking.customerEmail());
            helper.setSubject("How were your nails? We'd love to hear from you");
            helper.setText(buildReviewHtml(booking), true);

            mailSender.send(message);
            logger.info("Review request email sent to {} (booking #{})",
                    booking.customerEmail(), booking.id());
            return true;
        } catch (MessagingException | MailException e) {
            // Don't stamp: leave review_sent_at null so the next sweep retries this booking.
            logger.warn("Failed to send review request for booking #{}, will retry next sweep: {}",
                    booking.id(), e.getMessage());
            return false;
        }
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
        body.append("Thank you for booking with Every1Luvs\n");
        body.append("We can't wait to give your hands the love they deserve!\n\n");
        body.append("Here's a summary of your appointment:\n");
        body.append("Date: ").append(SLOT_DATE_FORMAT.format(booking.slotStartTime())).append('\n');
        body.append("Time: ").append(SLOT_TIME_FORMAT.format(booking.slotStartTime())).append('\n');
        body.append("Service: ").append(booking.serviceName()).append('\n');
        if (isMeaningful(booking.nailArt(), NO_NAIL_ART)) {
            body.append("Nail Art: ").append(booking.nailArt()).append('\n');
        }
        if (isMeaningful(booking.removal(), NO_REMOVAL)) {
            body.append("Removal: ").append(booking.removal()).append('\n');
        }
        body.append("Deposit Paid: S$").append(DEPOSIT_SGD).append(" (applied to your final bill)\n");
        body.append("Total Estimate: S$").append(booking.totalPrice()).append("\n\n");
        body.append("We'll send you our studio address in a separate email, 2 days before your appointment.\n\n");
        body.append("Need to reschedule or cancel? Just let us know at least 72 hours in advance.\n\n");
        body.append("We'll have you leaving with fresh nails and good vibes soon.\n\n");
        body.append("With Luv,\nEvery1Luvs\n");
        return body.toString();
    }

    private boolean isMeaningful(String value, String defaultName) {
        return value != null && !value.isBlank() && !value.equals(defaultName);
    }

    // HTML body for the pre-appointment reminder. Customer-supplied text (name) is escaped; the
    // rest is static studio copy. Time shows the start only, matching the confirmation.
    private String buildReminderHtml(BookingResponse booking) {
        String name = htmlEscape(safe(booking.userName()));
        String date = SLOT_DATE_FORMAT.format(booking.slotStartTime());
        String time = SLOT_TIME_FORMAT.format(booking.slotStartTime());
        return "<p>Hi " + name + ",</p>"
                + "<p>We can't wait to see you soon!</p>"
                + "<p>Here are your appointment details:</p>"
                + "<p>"
                + "Date: " + date + "<br>"
                + "Time: " + time + "<br>"
                + "Location: " + STUDIO_ADDRESS + "<br>"
                + "Nearest Parking / MRT: " + STUDIO_TRANSPORT
                + "</p>"
                + "<p>Please let us know when you arrive outside our studio, we will let you in!</p>"
                + "<p>Have any questions? DM us at "
                + "<a href=\"" + INSTAGRAM_URL + "\">" + INSTAGRAM_HANDLE + "</a></p>"
                + "<p>See you soon!</p>"
                + "<p>With Luv,<br>Every1Luvs</p>";
    }

    private String htmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // HTML body for the post-appointment review request. reviewUrl is guaranteed non-blank here
    // (guarded by isReviewRequestEnabled in the only caller); the customer name is HTML-escaped.
    private String buildReviewHtml(BookingResponse booking) {
        String name = htmlEscape(safe(booking.userName()));
        return "<p>Hi " + name + ",</p>"
                + "<p>Thank you for choosing Every1Luvs — we hope you're loving your fresh set!</p>"
                + "<p>Your feedback means the world to us and helps other clients discover the studio "
                + "too. Could you spare a minute to leave us a review?</p>"
                + "<p><a href=\"" + htmlEscape(reviewUrl) + "\">Leave a Review →</a></p>"
                + "<p>We'd also love to see your nails! Tag us on Instagram "
                + "<a href=\"" + INSTAGRAM_URL + "\">" + INSTAGRAM_HANDLE + "</a>.</p>"
                + "<p>Thank you again for trusting us with your hands.</p>"
                + "<p>With Luv,<br>Every1Luvs</p>";
    }
}
