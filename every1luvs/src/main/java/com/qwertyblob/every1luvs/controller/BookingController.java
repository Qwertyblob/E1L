package com.qwertyblob.every1luvs.controller;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.dto.PageResponse;
import com.qwertyblob.every1luvs.security.ClientIpResolver;
import com.qwertyblob.every1luvs.service.BookingMailService;
import com.qwertyblob.every1luvs.service.BookingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;
    private final BookingMailService bookingMailService;
    private final ClientIpResolver clientIpResolver;

    public BookingController(
            BookingService bookingService,
            BookingMailService bookingMailService,
            ClientIpResolver clientIpResolver
    ) {
        this.bookingService = bookingService;
        this.bookingMailService = bookingMailService;
        this.clientIpResolver = clientIpResolver;
    }

    // Public, CSRF-exempt guest booking. It is never attributed to a logged-in user, so a
    // forged cross-site request can at worst create an IP-rate-limited guest booking.
    @PostMapping("/bookings")
    public ResponseEntity<BookingResponse> createGuestBooking(
            @RequestBody CreateBookingRequest request,
            HttpServletRequest httpRequest
    ) {
        BookingResponse created = bookingService.createBooking(request, null, clientIp(httpRequest));
        // Post-commit (createBooking has returned) + @Async, so a mail failure can't fail the booking.
        bookingMailService.sendBookingConfirmation(created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // Authenticated booking goes through the CSRF-protected path (not in the CSRF ignore list),
    // so logged-in bookings keep CSRF defence even under SameSite=None.
    @PostMapping("/bookings/me")
    public ResponseEntity<BookingResponse> createMyBooking(
            @RequestBody CreateBookingRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest
    ) {
        BookingResponse created = bookingService.createBooking(request, authentication.getName(), clientIp(httpRequest));
        // Post-commit (createBooking has returned) + @Async, so a mail failure can't fail the booking.
        bookingMailService.sendBookingConfirmation(created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    @GetMapping("/bookings/my")
    public List<BookingResponse> getMyBookings(Authentication authentication) {
        return bookingService.getMyBookings(authentication.getName());
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingResponse cancelBooking(@PathVariable Long id, Authentication authentication) {
        return bookingService.cancelBooking(id, authentication.getName());
    }

    @GetMapping("/admin/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    public PageResponse<BookingResponse> getAllBookings(@PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(bookingService.getAllBookings(pageable));
    }

    @PostMapping("/admin/bookings/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public BookingResponse adminCancelBooking(@PathVariable Long id) {
        return bookingService.adminCancelBooking(id);
    }

    @PostMapping("/admin/bookings/{id}/complete")
    @PreAuthorize("hasRole('ADMIN')")
    public BookingResponse adminCompleteBooking(@PathVariable Long id) {
        return bookingService.adminCompleteBooking(id);
    }
}
