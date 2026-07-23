package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.DepositClaimRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DepositClaimServiceTest {

    private RateLimiterService rateLimiter;
    private BookingMailService mailService;
    private DepositClaimService service;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiterService.class);
        mailService = mock(BookingMailService.class);
        service = new DepositClaimService(rateLimiter, mailService);
    }

    @Test
    void submit_rateLimitsPerIpAndForwardsToAdminMail() {
        DepositClaimRequest request = new DepositClaimRequest(
                "Alice", "alice@example.com", "9123", "Classic Manicure", "2026-07-30", "13:30", "Slot taken");

        service.submit(request, "1.2.3.4");

        verify(rateLimiter).check(eq("deposit-claim:ip:1.2.3.4"), anyInt(), anyLong(), anyString());
        verify(mailService).sendDepositClaimNotification(any(DepositClaimRequest.class));
    }

    @Test
    void submit_rejectsMissingNameOrEmailWithoutSendingMail() {
        DepositClaimRequest noEmail = new DepositClaimRequest(
                "Alice", "  ", null, null, null, null, null);

        assertThatThrownBy(() -> service.submit(noEmail, "1.2.3.4"))
                .isInstanceOf(ResponseStatusException.class);
        verify(mailService, never()).sendDepositClaimNotification(any());
    }
}
