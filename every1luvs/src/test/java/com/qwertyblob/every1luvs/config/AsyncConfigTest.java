package com.qwertyblob.every1luvs.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.qwertyblob.every1luvs.service.OtpDeliveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AsyncConfig.class);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void asyncFailures_logGreppableMarkerWithoutLeakingArguments() throws Exception {
        AsyncUncaughtExceptionHandler handler = new AsyncConfig().getAsyncUncaughtExceptionHandler();
        Method mailMethod = OtpDeliveryService.class.getMethod("sendVerificationOtp", String.class, String.class);

        // Simulate a mail-send blowing up with an OTP among the @Async method's arguments.
        handler.handleUncaughtException(new IllegalStateException("SMTP down"),
                mailMethod, "alice@example.com", "123456");

        assertThat(appender.list).hasSize(1);
        String formatted = appender.list.get(0).getFormattedMessage();
        // The marker + origin make the failure greppable (scripts/check-mail-failures.sh)...
        assertThat(formatted).contains(AsyncConfig.FAILURE_MARKER)
                .contains("OtpDeliveryService")
                .contains("sendVerificationOtp");
        // ...but the OTP and recipient passed as arguments must never reach the logs.
        assertThat(formatted).doesNotContain("123456").doesNotContain("alice@example.com");
    }
}
