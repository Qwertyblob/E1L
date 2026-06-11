package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BookingMailServiceTest {

    private static final String FROM = "no-reply@every1luvs.test";

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final BookingMailService service = new BookingMailService(mailSender, FROM);

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
        assertThat(sent.getSubject()).contains("confirmed");

        String body = sent.getText();
        assertThat(body)
                .contains("Alice")
                .contains("#7")
                .contains("Classic Manicure")
                .contains("Senior Technician")
                .contains("S$60")
                .contains("15 Jun 2026")
                .contains("Tier 1 — Simple");          // meaningful nail-art line present
        assertThat(body).doesNotContain("No removal needed"); // default removal line omitted
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
}
