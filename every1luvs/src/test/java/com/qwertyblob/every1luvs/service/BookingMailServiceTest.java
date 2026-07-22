package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingMailServiceTest {

    private static final String FROM = "no-reply@every1luvs.test";
    private static final String ADMIN = "admin@every1luvs.test";
    private static final String REVIEW_URL = "https://g.page/r/every1luvs/review";
    private static final String BOOKING_URL = "https://every1luvs.com";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final BookingMailService service =
            new BookingMailService(mailSender, FROM, ADMIN, REVIEW_URL, BOOKING_URL);

    private static BookingResponse booking(String email, String nailArt, String removal) {
        return new BookingResponse(
                7L, 10L, "Manicure slot",
                Instant.parse("2026-06-15T14:00:00Z"), Instant.parse("2026-06-15T15:00:00Z"),
                1L, "Alice", email, "123", "@alice", "notes",
                "Classic Manicure", "Senior Technician", nailArt, removal,
                60, "BOOKED", Instant.EPOCH);
    }

    @Test
    void sendBookingConfirmation_sendsDetailedMailToCustomer() {
        service.sendBookingConfirmation(booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();

        assertThat(sent.getFrom()).isEqualTo(FROM);
        assertThat(sent.getTo()).containsExactly("alice@example.com");
        assertThat(sent.getSubject()).isEqualTo("Every1Luvs appointment confirmation");

        String body = sent.getText();
        assertThat(body)
                .contains("Alice")
                .contains("Classic Manicure")
                .contains("Deposit Paid: S$30 (applied to your final bill)")
                .contains("Total Estimate: S$60")
                .contains("15 Jun 2026")
                .contains("Time: 2:00 PM")               // start time only, no end time
                .contains("Tier 1 — Simple");          // meaningful nail-art line present
        assertThat(body).doesNotContain("3:00 PM");            // end time omitted
        assertThat(body).doesNotContain("No removal needed");  // default removal line omitted
        assertThat(body).doesNotContain("Senior Technician");  // technician tier removed
    }

    @Test
    void sendBookingConfirmation_omitsBothDefaultAddOns() {
        service.sendBookingConfirmation(
                booking("alice@example.com", "No design (plain colour only)", "No removal needed"));

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        String body = captor.getValue().getText();
        assertThat(body).doesNotContain("Nail art:");
        assertThat(body).doesNotContain("Removal:");
    }

    @Test
    void sendBookingConfirmation_blankEmail_doesNotSend() {
        service.sendBookingConfirmation(booking("  ", "Tier 1 — Simple", "No removal needed"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendBookingConfirmation_nullEmail_doesNotSend() {
        service.sendBookingConfirmation(booking(null, "Tier 1 — Simple", "No removal needed"));
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendBookingReminder_sendsHtmlWithStudioDetailsAndClickableInstagram() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        boolean sentOk = service.sendBookingReminder(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isTrue();
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        String html = sent.getContent().toString();
        assertThat(html)
                .contains("Hi Alice")
                .contains("15 Jun 2026")                        // appointment date
                .contains("2:00 PM")                            // start time
                .contains("Block 190 Lorong 6 Toa Payoh")       // studio address
                .contains("Toa Payoh HDB Hub Car Park")         // parking / MRT
                .contains("<a href=\"https://instagram.com/every1luvsnails\">@every1luvsnails</a>");
        assertThat(html).doesNotContain("3:00 PM");             // no end time
    }

    @Test
    void sendBookingReminder_sendFailure_returnsFalseSoCallerCanRetry() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        boolean sentOk = service.sendBookingReminder(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isFalse();
    }

    @Test
    void sendBookingReminder_blankEmail_settledWithoutSending() {
        boolean sentOk = service.sendBookingReminder(booking("  ", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isTrue(); // nothing to send, but don't keep reprocessing it
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendReviewRequest_sendsHtmlWithClickableReviewLink() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        boolean sentOk = service.sendReviewRequest(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isTrue();
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("How were your nails? We'd love to hear from you");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        String html = sent.getContent().toString();
        assertThat(html)
                .contains("Hi Alice")
                .contains("<a href=\"" + REVIEW_URL + "\">Leave a Review →</a>")
                .contains("<a href=\"https://instagram.com/every1luvsnails\">@every1luvsnails</a>");
    }

    @Test
    void sendReviewRequest_disabledWhenNoReviewUrl_doesNotSend() {
        BookingMailService dormant = new BookingMailService(mailSender, FROM, ADMIN, "", BOOKING_URL);

        boolean sentOk = dormant.sendReviewRequest(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isFalse();           // not sent, and caller must NOT stamp it
        assertThat(dormant.isReviewRequestEnabled()).isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendReviewRequest_sendFailure_returnsFalseSoCallerCanRetry() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        boolean sentOk = service.sendReviewRequest(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isFalse();
    }

    @Test
    void sendRebookingPrompt_sendsHtmlWithClickableBookingLink() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        boolean sentOk = service.sendRebookingPrompt(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isTrue();
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getSubject()).isEqualTo("Nail Refresh Time?");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("alice@example.com");
        String html = sent.getContent().toString();
        assertThat(html)
                .contains("Hi Alice")
                .contains("<a href=\"" + BOOKING_URL + "\">Book Your Next Appointment →</a>");
    }

    @Test
    void sendRebookingPrompt_disabledWhenNoBookingUrl_doesNotSend() {
        BookingMailService dormant = new BookingMailService(mailSender, FROM, ADMIN, REVIEW_URL, "");

        boolean sentOk = dormant.sendRebookingPrompt(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isFalse();           // not sent, and caller must NOT stamp it
        assertThat(dormant.isRebookingEnabled()).isFalse();
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void sendRebookingPrompt_sendFailure_returnsFalseSoCallerCanRetry() {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));

        boolean sentOk = service.sendRebookingPrompt(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"));

        assertThat(sentOk).isFalse();
    }

    @Test
    void sendAdminBookingNotification_sendsToAdminWithPhotoAttached() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());
        // Server-owned sanitized image (ImageSanitizer's output) — the mail service attaches its
        // bytes/type/name verbatim, so a real raster isn't needed here.
        SanitizedImage image = new SanitizedImage("inspo-1.png", "image/png", new byte[]{1, 2, 3});

        service.sendAdminBookingNotification(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"), List.of(image));

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        MimeMessage sent = captor.getValue();

        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo(ADMIN);
        assertThat(sent.getSubject()).contains("#7").contains("Alice");
    }

    @Test
    void sendAdminBookingNotification_sendsEvenWithNoPhotos() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new JavaMailSenderImpl().createMimeMessage());

        service.sendAdminBookingNotification(
                booking("alice@example.com", "Tier 1 — Simple", "No removal needed"), List.of());

        verify(mailSender).send(any(MimeMessage.class));
    }
}
