package com.qwertyblob.every1luvs.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OtpDeliveryServiceTest {

    @Mock JavaMailSender mailSender;

    private SimpleMailMessage captureSent() {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void sendVerificationOtp_sendsEmailWithOtpAndFromAddress() {
        OtpDeliveryService service = new OtpDeliveryService(mailSender, false, "noreply@every1luvs.com");

        service.sendVerificationOtp("alice@example.com", "123456");

        SimpleMailMessage sent = captureSent();
        assertThat(sent.getFrom()).isEqualTo("noreply@every1luvs.com");
        assertThat(sent.getTo()).containsExactly("alice@example.com");
        assertThat(sent.getSubject()).isEqualTo("Your every1luvs Verification Code");
        assertThat(sent.getText()).contains("123456");
    }

    @Test
    void sendVerificationOtp_logToConsoleEnabled_stillSendsEmail() {
        // exercises the logToConsole == true branch
        OtpDeliveryService service = new OtpDeliveryService(mailSender, true, "noreply@every1luvs.com");

        service.sendVerificationOtp("bob@example.com", "654321");

        SimpleMailMessage sent = captureSent();
        assertThat(sent.getTo()).containsExactly("bob@example.com");
        assertThat(sent.getText()).contains("654321");
    }

    @Test
    void sendPasswordResetCode_sendsEmailWithCode() {
        OtpDeliveryService service = new OtpDeliveryService(mailSender, false, "noreply@every1luvs.com");

        service.sendPasswordResetCode("alice@example.com", "246813");

        SimpleMailMessage sent = captureSent();
        assertThat(sent.getFrom()).isEqualTo("noreply@every1luvs.com");
        assertThat(sent.getTo()).containsExactly("alice@example.com");
        assertThat(sent.getSubject()).isEqualTo("Your every1luvs password reset code");
        assertThat(sent.getText()).contains("246813");
    }
}
