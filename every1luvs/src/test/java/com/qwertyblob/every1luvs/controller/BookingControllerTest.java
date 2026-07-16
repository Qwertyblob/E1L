package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.dto.PageResponse;
import com.qwertyblob.every1luvs.security.ClientIpResolver;
import com.qwertyblob.every1luvs.service.BookingMailService;
import com.qwertyblob.every1luvs.service.BookingService;
import com.qwertyblob.every1luvs.service.ImageSanitizer;
import com.qwertyblob.every1luvs.service.ReviewRequestService;
import com.qwertyblob.every1luvs.service.SanitizedImage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock BookingService bookingService;
    @Mock BookingMailService bookingMailService;
    @Mock ReviewRequestService reviewRequestService;
    @Mock ImageSanitizer imageSanitizer;

    // Default controller trusts only the peer address (trustForwardedFor=false); the
    // trust-enabled variant is constructed inline in its own test.
    BookingController bookingController;

    @BeforeEach
    void setUp() {
        bookingController = new BookingController(
                bookingService, bookingMailService, reviewRequestService, imageSanitizer,
                new ClientIpResolver(false));
    }

    private static BookingResponse booking(Long id, String status) {
        return new BookingResponse(id, 10L, "Slot", Instant.EPOCH, Instant.EPOCH, 1L, "Alice",
                "alice@example.com", "123", "@alice", "notes", "Manicure", "Tech", "art", "none",
                5000, status, Instant.EPOCH);
    }

    // ─── createBooking ───────────────────────────────────────────────────────────

    @Test
    void createGuestBooking_usesRemoteAddrWhenForwardedNotTrusted_returns201() {
        // @InjectMocks builds the controller with trustForwardedFor=false, so X-Forwarded-For
        // is ignored and the limiter key comes from the real peer address.
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("203.0.113.5");
        CreateBookingRequest request = new CreateBookingRequest(10L);
        BookingResponse created = booking(2L, "BOOKED");
        when(bookingService.createBooking(request, null, "203.0.113.5")).thenReturn(created);
        List<SanitizedImage> sanitized = List.of(new SanitizedImage("inspo-1.jpg", "image/jpeg", new byte[]{1}));
        when(imageSanitizer.sanitize(request.attachments())).thenReturn(sanitized);

        ResponseEntity<BookingResponse> response = bookingController.createGuestBooking(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(created);
        verify(bookingMailService).sendBookingConfirmation(created);
        // The admin notification gets the SANITIZED images, never the raw client attachments.
        verify(bookingMailService).sendAdminBookingNotification(eq(created), eq(sanitized));
    }

    @Test
    void createMyBooking_usesAuthEmailAndRemoteAddr_returns201() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getRemoteAddr()).thenReturn("198.51.100.7");
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice@example.com");
        CreateBookingRequest request = new CreateBookingRequest(10L);
        BookingResponse created = booking(1L, "BOOKED");
        when(bookingService.createBooking(request, "alice@example.com", "198.51.100.7")).thenReturn(created);
        List<SanitizedImage> sanitized = List.of(new SanitizedImage("inspo-1.png", "image/png", new byte[]{2}));
        when(imageSanitizer.sanitize(request.attachments())).thenReturn(sanitized);

        ResponseEntity<BookingResponse> response = bookingController.createMyBooking(request, auth, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(created);
        verify(bookingMailService).sendBookingConfirmation(created);
        verify(bookingMailService).sendAdminBookingNotification(eq(created), eq(sanitized));
    }

    @Test
    void createGuestBooking_trustsForwardedForWhenEnabled_returns201() {
        // With trust enabled (behind a trusted proxy), the first X-Forwarded-For hop is used.
        BookingController trustingController =
                new BookingController(bookingService, bookingMailService, reviewRequestService, imageSanitizer,
                        new ClientIpResolver(true));
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("198.51.100.7, 10.0.0.1");
        CreateBookingRequest request = new CreateBookingRequest(10L);
        BookingResponse created = booking(2L, "BOOKED");
        when(bookingService.createBooking(request, null, "198.51.100.7")).thenReturn(created);

        ResponseEntity<BookingResponse> response = trustingController.createGuestBooking(request, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(created);
    }

    // ─── user-scoped reads/cancel ────────────────────────────────────────────────

    @Test
    void getMyBookings_usesAuthenticatedEmail() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice@example.com");
        List<BookingResponse> expected = List.of(booking(1L, "BOOKED"));
        when(bookingService.getMyBookings("alice@example.com")).thenReturn(expected);

        assertThat(bookingController.getMyBookings(auth)).isEqualTo(expected);
    }

    @Test
    void cancelBooking_usesIdAndAuthenticatedEmail() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice@example.com");
        BookingResponse cancelled = booking(1L, "CANCELLED");
        when(bookingService.cancelBooking(1L, "alice@example.com")).thenReturn(cancelled);

        assertThat(bookingController.cancelBooking(1L, auth)).isEqualTo(cancelled);
        // Confirmation mail is for new bookings only — cancelling must not send one.
        verify(bookingMailService, never()).sendBookingConfirmation(any());
    }

    // ─── admin endpoints ─────────────────────────────────────────────────────────

    @Test
    void getAllBookings_delegatesToServiceAndWrapsPage() {
        List<BookingResponse> bookings = List.of(booking(1L, "BOOKED"), booking(2L, "CANCELLED"));
        Pageable pageable = PageRequest.of(0, 50);
        Page<BookingResponse> page = new PageImpl<>(bookings, pageable, 2);
        when(bookingService.getAllBookings(pageable)).thenReturn(page);

        PageResponse<BookingResponse> result = bookingController.getAllBookings(pageable);

        assertThat(result.content()).isEqualTo(bookings);
        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.number()).isZero();
    }

    @Test
    void adminCancelBooking_delegatesToService() {
        BookingResponse cancelled = booking(1L, "CANCELLED");
        when(bookingService.adminCancelBooking(1L)).thenReturn(cancelled);

        assertThat(bookingController.adminCancelBooking(1L)).isEqualTo(cancelled);
    }

    @Test
    void adminCompleteBooking_delegatesToServiceAndTriggersReviewRequest() {
        BookingResponse completed = booking(1L, "COMPLETED");
        when(bookingService.adminCompleteBooking(1L)).thenReturn(completed);

        assertThat(bookingController.adminCompleteBooking(1L)).isEqualTo(completed);
        // Completing a booking kicks off the post-appointment review request (dormant unless enabled).
        verify(reviewRequestService).sendReviewRequestNow(completed);
    }
}
