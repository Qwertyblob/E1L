package com.qwertyblob.every1luvs.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class OtpDeliveryService {
    private static final Logger logger = LoggerFactory.getLogger(OtpDeliveryService.class);

    private final JavaMailSender mailSender;
    private final boolean logToConsole;
    private final String fromAddress;

    public OtpDeliveryService(
            JavaMailSender mailSender,
            @Value("${app.auth.otp.log-to-console}") boolean logToConsole,
            @Value("${app.mail.from}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.logToConsole = logToConsole;
        this.fromAddress = fromAddress;
    }

    @Async
    public void sendVerificationOtp(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Your every1luvs Verification Code");
        message.setText("""
                Your every1luvs verification code is %s.

                This code expires in 10 minutes. If you did not request this account, you can ignore this email.
                """.formatted(otp));

        mailSender.send(message);

        if (logToConsole) {
            logger.info("Verification OTP for {} is {}", email, otp);
        }
        logger.info("Verification OTP email sent to {}", email);
    }

    @Async
    public void sendPasswordResetCode(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Your every1luvs password reset code");
        message.setText("""
                Use this code to reset your every1luvs password: %s

                This code expires in 10 minutes. Enter it on the password reset screen
                along with your new password. If you did not request this, you can ignore
                this email — your password has not been changed.
                """.formatted(otp));

        mailSender.send(message);

        if (logToConsole) {
            logger.info("Password reset code for {} is {}", email, otp);
        }
        logger.info("Password reset code email sent to {}", email);
    }

    @Async
    public void sendGuestBookingOtp(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("Your every1luvs booking verification code");
        message.setText("""
                Your every1luvs booking verification code is %s.

                Enter it on the booking screen to confirm your appointment. The code expires
                in 10 minutes. If you did not try to book with us, you can ignore this email.
                """.formatted(otp));

        mailSender.send(message);

        if (logToConsole) {
            logger.info("Guest booking OTP for {} is {}", email, otp);
        }
        logger.info("Guest booking OTP email sent to {}", email);
    }

    @Async
    public void sendAccountExists(String email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("You already have an every1luvs account");
        message.setText("""
                Someone tried to register an every1luvs account with this email address,
                but an account already exists.

                If this was you, simply sign in. If you've forgotten your password, use the
                "Forgot password?" option on the sign-in screen. If this wasn't you, you can
                safely ignore this email.
                """);

        mailSender.send(message);
        logger.info("Account-exists notice sent to {}", email);
    }
}
