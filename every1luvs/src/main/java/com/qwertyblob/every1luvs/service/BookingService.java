package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BookingService {

    // Guest (anonymous) bookings are unauthenticated, so cap them per client IP to stop
    // a script from filling every slot to capacity.
    private static final int MAX_GUEST_BOOKINGS = 5;
    private static final long GUEST_BOOKING_WINDOW_MS = 60 * 60 * 1_000L;

    // Contact-field bounds. The length caps mirror the tbl_bookings columns
    // (customer_name/customer_email/instagram VARCHAR(255), phone VARCHAR(64),
    // notes TEXT — capped here too so a huge body can't store junk), so overlong or
    // malformed public input is rejected with 400 instead of surfacing as a DB 500.
    private static final int MAX_CUSTOMER_NAME_LENGTH = 255;
    private static final int MAX_CUSTOMER_EMAIL_LENGTH = 255;
    private static final int MAX_PHONE_LENGTH = 64;
    private static final int MAX_INSTAGRAM_LENGTH = 255;
    private static final int MAX_NOTES_LENGTH = 2_000;
    // Pragmatic format gate: non-empty local part, single @, a dotted domain, no spaces.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final UserRepository userRepository;
    private final RateLimiterService rateLimiter;

    public BookingService(
            BookingRepository bookingRepository,
            SlotRepository slotRepository,
            UserRepository userRepository,
            RateLimiterService rateLimiter
    ) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
    }

    public BookingResponse createBooking(CreateBookingRequest request, String email) {
        return createBooking(request, email, null);
    }

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request, String email, String clientIp) {
        if (request == null || request.slotId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slot ID is required.");
        }

        UserEntity user = null;
        if (email != null) {
            user = loadUserOrThrow(email);
        } else {
            if (isBlank(request.customerName()) || isBlank(request.customerEmail())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and email are required to book.");
            }
            if (request.customerName().trim().length() > MAX_CUSTOMER_NAME_LENGTH) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is too long.");
            }
            String guestEmail = request.customerEmail().trim();
            if (guestEmail.length() > MAX_CUSTOMER_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(guestEmail).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email is required to book.");
            }
            if (clientIp != null && !clientIp.isBlank()) {
                rateLimiter.check("booking:" + clientIp, MAX_GUEST_BOOKINGS, GUEST_BOOKING_WINDOW_MS,
                        "Too many booking attempts. Please try again later.");
            }
        }

        // phone / instagram / notes are accepted for both guest and authenticated
        // bookings, so length-check them here regardless of who is booking.
        validateOptionalLength(request.phone(), MAX_PHONE_LENGTH, "Phone number");
        validateOptionalLength(request.instagram(), MAX_INSTAGRAM_LENGTH, "Instagram handle");
        validateOptionalLength(request.notes(), MAX_NOTES_LENGTH, "Notes");

        // Recompute service/add-on names and price from the server catalog so a caller can't
        // submit fake pricing; the client-sent serviceName/technician/nailArt/removal/totalPrice
        // are ignored in favour of these authoritative values.
        BookingQuote quote = priceFromCatalog(request);

        SlotEntity slot = loadSlotOrThrow(request.slotId());

        // Slot times are stored as UTC wall-clock; require the slot to start no earlier
        // than the beginning of tomorrow (UTC), so same-day and past slots can't be booked.
        Instant earliestStart = BookingWindow.earliestBookableStartUtc();
        if (slot.getStartTime() == null || slot.getStartTime().isBefore(earliestStart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Slots must be booked at least one day in advance.");
        }

        if (user != null) {
            if (bookingRepository.existsActiveBookingForUserAndSlot(slot.getId(), user.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an active booking for this slot.");
            }
        } else if (bookingRepository.existsActiveGuestBookingForSlotAndEmail(
                slot.getId(), request.customerEmail().trim())) {
            // Guests aren't covered by the user/slot unique index, so block a repeat
            // booking for the same email + slot before it consumes another seat.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This email already has a booking for this slot.");
        }

        if (slot.getBookedCount() >= slot.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This slot is fully booked.");
        }

        slot.setBookedCount(slot.getBookedCount() + 1);

        try {
            // saveAndFlush forces the version-checked UPDATE to fire now (inside this
            // try) so a concurrent-booking optimistic-lock conflict is caught here and
            // mapped to 409, instead of escaping at transaction commit as a 500.
            slotRepository.saveAndFlush(slot);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This slot is fully booked. Please try again.");
        }

        BookingEntity booking = new BookingEntity();
        booking.setSlotId(slot.getId());
        if (user != null) {
            booking.setUserId(user.getId());
            booking.setCustomerName(user.getName());
            booking.setCustomerEmail(user.getEmail());
        } else {
            booking.setCustomerName(request.customerName().trim());
            booking.setCustomerEmail(request.customerEmail().trim());
        }
        booking.setPhone(trimToNull(request.phone()));
        booking.setInstagram(trimToNull(request.instagram()));
        booking.setNotes(trimToNull(request.notes()));
        booking.setServiceName(quote.serviceName());
        booking.setTechnician(quote.technician());
        booking.setNailArt(quote.nailArt());
        booking.setRemoval(quote.removal());
        booking.setTotalPrice(quote.totalPrice());
        bookingRepository.save(booking);

        return toResponse(booking, slot, user);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Optional contact fields: blank/absent is fine, but reject anything that would
    // overflow its bounded column once trimmed.
    private void validateOptionalLength(String value, int maxLength, String fieldName) {
        if (value != null && value.trim().length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " is too long.");
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record BookingQuote(String serviceName, String technician, String nailArt,
                                String removal, int totalPrice) {
    }

    // Resolve canonical names + price from the server catalog, rejecting unknown selections.
    private BookingQuote priceFromCatalog(CreateBookingRequest request) {
        BookingCatalog.Service service = BookingCatalog.service(request.serviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown or missing service selection."));
        if (!BookingCatalog.isValidTechnicianLevel(request.technicianLevel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown technician level.");
        }
        BookingCatalog.AddOn nailArt = BookingCatalog.nailArt(request.nailArtId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown nail art selection."));
        BookingCatalog.AddOn removal = BookingCatalog.removal(request.removalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown removal selection."));

        // Non-nail services (e.g. CoolSculpting) can't carry nail-art / removal add-ons, so
        // reject anything other than the "none" default rather than recording a nonsensical
        // CoolSculpting + nail-art booking and total.
        if (!service.supportsAddOns()
                && (!BookingCatalog.DEFAULT_ADD_ON.equals(nailArt.id())
                    || !BookingCatalog.DEFAULT_ADD_ON.equals(removal.id()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add-ons are not available for this service.");
        }

        int total = BookingCatalog.servicePrice(service, request.technicianLevel())
                + nailArt.price() + removal.price();
        return new BookingQuote(service.name(), BookingCatalog.technicianLabel(request.technicianLevel()),
                nailArt.name(), removal.name(), total);
    }

    @Transactional
    public BookingResponse cancelBooking(Long bookingId, String email) {
        UserEntity user = loadUserOrThrow(email);

        BookingEntity booking = bookingRepository.findActiveBookingByIdAndUserId(bookingId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));

        booking.setStatus("CANCELLED");
        bookingRepository.save(booking);

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());
        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        releaseSeatOrConflict(slot);

        return toResponse(booking, slot, user);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> getMyBookings(String email) {
        UserEntity user = loadUserOrThrow(email);
        List<BookingEntity> bookings = bookingRepository.findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(user.getId());
        Map<Long, SlotEntity> slotsById = slotsByIdFor(bookings);
        return bookings.stream()
                .map(b -> toResponse(b, requireSlot(slotsById, b.getSlotId()), user))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> getAllBookings(Pageable pageable) {
        Page<BookingEntity> page = bookingRepository.findByArchivedAtIsNullOrderByCreatedAtDesc(pageable);
        // Batch-load the slots and users referenced by this page up front (two queries) instead
        // of a findById per booking, which was an N+1 that grew with the page size.
        Map<Long, SlotEntity> slotsById = slotsByIdFor(page.getContent());
        Map<Long, UserEntity> usersById = usersByIdFor(page.getContent());
        return page.map(b -> {
            UserEntity user = b.getUserId() != null ? usersById.get(b.getUserId()) : null;
            return toResponse(b, requireSlot(slotsById, b.getSlotId()), user);
        });
    }

    // Fetch every distinct slot referenced by these bookings in a single findAllById, keyed by id.
    private Map<Long, SlotEntity> slotsByIdFor(List<BookingEntity> bookings) {
        List<Long> slotIds = bookings.stream().map(BookingEntity::getSlotId).distinct().toList();
        return slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(SlotEntity::getId, Function.identity()));
    }

    // Fetch every distinct (non-null) user referenced by these bookings in a single findAllById.
    private Map<Long, UserEntity> usersByIdFor(List<BookingEntity> bookings) {
        List<Long> userIds = bookings.stream()
                .map(BookingEntity::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
    }

    // Preserve the prior loadSlotOrThrow semantics: a booking pointing at a missing slot is a
    // data-integrity error, so surface 404 rather than rendering a half-null response.
    private SlotEntity requireSlot(Map<Long, SlotEntity> slotsById, Long slotId) {
        SlotEntity slot = slotsById.get(slotId);
        if (slot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found.");
        }
        return slot;
    }

    @Transactional
    public BookingResponse adminCancelBooking(Long bookingId) {
        BookingEntity booking = bookingRepository.findByIdAndStatus(bookingId, "BOOKED")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));

        booking.setStatus("CANCELLED");
        bookingRepository.save(booking);

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());
        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        releaseSeatOrConflict(slot);

        UserEntity user = booking.getUserId() != null
                ? userRepository.findById(booking.getUserId()).orElse(null)
                : null;
        return toResponse(booking, slot, user);
    }

    @Transactional
    public BookingResponse adminCompleteBooking(Long bookingId) {
        BookingEntity booking = bookingRepository.findByIdAndStatus(bookingId, "BOOKED")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));

        booking.setStatus("COMPLETED");
        bookingRepository.save(booking);

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());
        UserEntity user = booking.getUserId() != null
                ? userRepository.findById(booking.getUserId()).orElse(null)
                : null;
        return toResponse(booking, slot, user);
    }

    private UserEntity loadUserOrThrow(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
    }

    private SlotEntity loadSlotOrThrow(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found."));
    }

    // Persist a seat-release (bookedCount decrement) on cancellation. saveAndFlush forces
    // the version-checked UPDATE to fire now so a concurrent cancel/booking optimistic-lock
    // conflict surfaces here as a retryable 409, instead of escaping at transaction commit
    // as a 500 (which would also silently roll back the booking's CANCELLED status).
    private void releaseSeatOrConflict(SlotEntity slot) {
        try {
            slotRepository.saveAndFlush(slot);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Slot was modified concurrently. Please try again.");
        }
    }

    private BookingResponse toResponse(BookingEntity booking, SlotEntity slot, UserEntity user) {
        String displayName = user != null ? user.getName() : booking.getCustomerName();
        String email = user != null ? user.getEmail() : booking.getCustomerEmail();
        return new BookingResponse(
                booking.getId(),
                slot.getId(),
                slot.getTitle(),
                slot.getStartTime(),
                slot.getEndTime(),
                user != null ? user.getId() : null,
                displayName,
                email,
                booking.getPhone(),
                booking.getInstagram(),
                booking.getNotes(),
                booking.getServiceName(),
                booking.getTechnician(),
                booking.getNailArt(),
                booking.getRemoval(),
                booking.getTotalPrice(),
                booking.getStatus(),
                booking.getCreatedAt()
        );
    }
}
