package com.qwertyblob.every1luvs.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * Makes {@code @Async} failures operationally visible. All current async tasks are
 * mail sends (OTP, reset code, booking confirmation): their exceptions never reach a
 * caller, so without this they vanish into a generic default log line. The stable
 * {@link #FAILURE_MARKER} prefix is what scripts/check-mail-failures.sh greps for —
 * change them together.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    static final String FAILURE_MARKER = "ASYNC_TASK_FAILURE";

    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // Method params are deliberately NOT logged: mail senders receive OTPs and
        // password-reset codes as arguments, which must never end up in log files.
        return (exception, method, params) -> logger.error(
                "{} {}.{} threw — if this is a mail sender, the message was NOT delivered",
                FAILURE_MARKER,
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                exception);
    }
}
