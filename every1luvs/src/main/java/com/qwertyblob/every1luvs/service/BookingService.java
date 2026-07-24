package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.BookingAttachment;
import com.qwertyblob.every1luvs.dto.BookingResponse;
import com.qwertyblob.every1luvs.dto.CreateBookingRequest;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SchedulingLockRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BookingService {

    // Booking-abuse limits. The per-IP bucket bounds anonymous floods (a script filling every
    // slot); the per-user bucket + the active-booking cap bound a single authenticated account,
    // which would otherwise bypass the limiter entirely. Both windows are one hour.
    private static final int MAX_BOOKINGS_PER_IP = 5;
    private static final int MAX_BOOKINGS_PER_USER = 10;
    private static final long BOOKING_WINDOW_MS = 60 * 60 * 1_000L;

    // Contact-field bounds. The length caps mirror the tbl_bookings columns
    // (customer_name/customer_email/instagram VARCHAR(255), phone VARCHAR(64),
    // notes TEXT — capped here too so a huge body can't store junk), so overlong or
    // malformed public input is rejected with 400 instead of surfacing as a DB 500.
    private static final int MAX_CUSTOMER_NAME_LENGTH = 255;
    private static final int MAX_CUSTOMER_EMAIL_LENGTH = 255;
    private static final int MAX_PHONE_LENGTH = 64;
    private static final int MAX_INSTAGRAM_LENGTH = 255;
    private static final int MAX_NOTES_LENGTH = 2_000;
    // Inspo image caps. Images are emailed to the salon, never stored, so bound the count and
    // (decoded) size to keep the public request body small and reject non-images. Keep these in
    // step with the client-side checks in BookingModal.jsx and nginx client_max_body_size.
    private static final int MAX_ATTACHMENTS = 5;
    private static final long MAX_ATTACHMENT_BYTES = 5L * 1024 * 1024;
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 15L * 1024 * 1024;
    // Pragmatic format gate: non-empty local part, single @, a dotted domain, no spaces.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final UserRepository userRepository;
    private final RateLimiterService rateLimiter;
    private final SchedulingGuard schedulingGuard;
    private final SchedulingLockRepository schedulingLockRepository;

    // Max concurrent UPCOMING bookings a single account may hold. The initializer is the same as
    // the property default so unit tests (which construct this bean directly) get the real cap.
    @Value("${app.booking.max-active-per-user:5}")
    private int maxActiveBookingsPerUser = 5;

    public BookingService(
            BookingRepository bookingRepository,
            SlotRepository slotRepository,
            UserRepository userRepository,
            RateLimiterService rateLimiter,
            SchedulingGuard schedulingGuard,
            SchedulingLockRepository schedulingLockRepository
    ) {
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.schedulingGuard = schedulingGuard;
        this.schedulingLockRepository = schedulingLockRepository;
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
        }

        // Booking-abuse limits apply to BOTH paths (authenticated bookings used to bypass this).
        // Separate buckets: one per client IP, one per user account.
        if (clientIp != null && !clientIp.isBlank()) {
            rateLimiter.check("booking:ip:" + clientIp, MAX_BOOKINGS_PER_IP, BOOKING_WINDOW_MS,
                    "Too many booking attempts. Please try again later.");
        }
        if (user != null) {
            rateLimiter.check("booking:user:" + user.getId(), MAX_BOOKINGS_PER_USER, BOOKING_WINDOW_MS,
                    "Too many booking attempts. Please try again later.");
        }

        // phone / instagram / notes are accepted for both guest and authenticated
        // bookings, so length-check them here regardless of who is booking.
        validateOptionalLength(request.phone(), MAX_PHONE_LENGTH, "Phone number");
        validateOptionalLength(request.instagram(), MAX_INSTAGRAM_LENGTH, "Instagram handle");
        validateOptionalLength(request.notes(), MAX_NOTES_LENGTH, "Notes");
        validateAttachments(request.attachments());

        // Recompute service/add-on names and price from the server catalog so a caller can't
        // submit fake pricing; the client-sent serviceName/technician/nailArt/removal/totalPrice
        // are ignored in favour of these authoritative values.
        BookingQuote quote = priceFromCatalog(request);

        // Take the shared scheduling lock BEFORE reading the slot/capacity so this confirmation
        // sees a consistent snapshot and can't race another confirmation (or a slot mutation) into
        // an over-capacity schedule. Fixed lock order — scheduling lock first, then the per-user
        // row lock below — avoids deadlock.
        schedulingLockRepository.acquire();

        SlotEntity slot = loadSlotOrThrow(request.slotId());

        // Slot times are stored as UTC wall-clock; require the slot to start no earlier
        // than the beginning of tomorrow (UTC), so same-day and past slots can't be booked.
        Instant earliestStart = BookingWindow.earliestBookableStartUtc();
        if (slot.getStartTime() == null || slot.getStartTime().isBefore(earliestStart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Slots must be booked at least one day in advance.");
        }

        if (user != null) {
            // Lock the user's row (serializing their concurrent bookings for the cap check) and
            // re-read it under the lock. The user was loaded before the scheduling lock, so a
            // concurrent account deletion holding the lock could have removed the row since; using
            // the freshly-locked entity turns that into a clean 401 here instead of a misleading
            // FK-constraint 409/500 at insert time.
            user = userRepository.findByIdForUpdate(user.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
            long upcoming = bookingRepository.countActiveFutureBookingsForUser(
                    user.getId(), BookingWindow.currentBusinessWallClockUtc());
            if (upcoming >= maxActiveBookingsPerUser) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "You already have the maximum number of upcoming bookings.");
            }
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

        // SchedulingGuard is the sole authority for time/capacity safety. This booking occupies
        // [start, start + durationMin); reject if adding it would push concurrent appointments past
        // capacity at any instant of that window. The picked slot's own capacity is one of the
        // tiles, so this subsumes the old bookedCount >= capacity check. Held under the scheduling
        // lock, so the snapshot we check against can't change before we insert.
        Instant occupiedStart = slot.getStartTime();
        Instant occupiedEnd = occupiedEnd(occupiedStart, slot.getEndTime(), quote.durationMin());
        if (!schedulingGuard.canAddAppointment(occupiedStart, occupiedEnd,
                activeAppointmentsBefore(occupiedEnd), activeCapacityTiles(occupiedStart, occupiedEnd))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This time is fully booked.");
        }

        // bookedCount is a display-only count of active direct bookings on this slot — never the
        // capacity authority. Bump the picked slot's counter, flushing now so a concurrent cancel's
        // optimistic-version change surfaces here as a retryable 409, not a commit-time 500.
        slot.setBookedCount(slot.getBookedCount() + 1);
        try {
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
        booking.setNailArt(quote.nailArt());
        booking.setRemoval(quote.removal());
        booking.setTotalPrice(quote.totalPrice());
        // Persist the duration so the SchedulingGuard invariant can be re-evaluated against this
        // booking's occupied interval on future confirmations and in the conflict audit.
        booking.setDurationMin(quote.durationMin());

        try {
            // The exists-checks above are TOCTOU; the partial unique indexes
            // (uq_bookings_active_user_slot / uq_bookings_active_guest_slot_email) are the
            // real guard against a concurrent duplicate. Translate their violation to a 409
            // so a racing double-submit surfaces as a conflict, not a 500. The whole
            // transaction (including the seat increment above) rolls back on the way out.
            bookingRepository.save(booking);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You already have an active booking for this slot.");
        }

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

    // Inspo images ride along with the booking only to be emailed to the salon; they are never
    // persisted. Reject too many, too large, or non-image payloads with 400 (before the booking
    // is created) so a public caller can't post an oversized or hostile body.
    private void validateAttachments(List<BookingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        if (attachments.size() > MAX_ATTACHMENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You can attach at most " + MAX_ATTACHMENTS + " images.");
        }
        long total = 0;
        for (BookingAttachment attachment : attachments) {
            if (attachment == null || isBlank(attachment.data())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An attached image was empty.");
            }
            String contentType = attachment.contentType();
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only image attachments are allowed.");
            }
            long bytes = decodedBase64Length(attachment.data());
            if (bytes > MAX_ATTACHMENT_BYTES) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each image must be 5 MB or smaller.");
            }
            total += bytes;
        }
        if (total > MAX_TOTAL_ATTACHMENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your images are too large in total. Please remove some and try again.");
        }
    }

    // Estimate the decoded byte length of a Base64 string without allocating the decoded array.
    private long decodedBase64Length(String base64) {
        int len = base64.length();
        int padding = 0;
        if (len > 0 && base64.charAt(len - 1) == '=') padding++;
        if (len > 1 && base64.charAt(len - 2) == '=') padding++;
        return (long) len * 3 / 4 - padding;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record BookingQuote(String serviceName, String nailArt,
                                String removal, int totalPrice, int durationMin) {
    }

    // Resolve canonical names + price + total duration from the server catalog, rejecting
    // unknown selections. durationMin (service + nail art + removal) defines the booking's
    // occupied interval for SchedulingGuard, so it — like the price — is computed here from the
    // authoritative catalog, never trusted from the client.
    private BookingQuote priceFromCatalog(CreateBookingRequest request) {
        BookingCatalog.Service service = BookingCatalog.service(request.serviceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown or missing service selection."));
        BookingCatalog.AddOn nailArt = BookingCatalog.nailArt(request.nailArtId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown nail art selection."));
        BookingCatalog.AddOn removal = BookingCatalog.removal(request.removalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown removal selection."));

        int total = service.price() + nailArt.price() + removal.price();
        int durationMin = service.durationMin() + nailArt.durationMin() + removal.durationMin();
        return new BookingQuote(service.name(), nailArt.name(), removal.name(), total, durationMin);
    }

    @Transactional
    public BookingResponse cancelBooking(Long bookingId, String email) {
        UserEntity user = loadUserOrThrow(email);

        // 404 for a missing or non-owned booking (don't reveal another user's booking); 409 if it
        // exists but is no longer BOOKED (already cancelled/completed).
        BookingEntity booking = bookingRepository.findByIdAndUserIdAndArchivedAtIsNull(bookingId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));
        if (!"BOOKED".equals(booking.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking can no longer be cancelled.");
        }

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());

        // Customers must give at least 72 hours' notice (adminCancelBooking stays
        // unrestricted). Slot times are wall-clock-as-UTC, so compare against "now"
        // expressed in the same convention.
        if (slot.getStartTime() == null
                || slot.getStartTime().isBefore(BookingWindow.earliestCancellableStartUtc())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bookings can only be cancelled at least 72 hours before the appointment.");
        }

        // Atomic guarded transition: only one of a racing cancel/complete can flip BOOKED.
        if (bookingRepository.transitionFromBookedForUser(bookingId, user.getId(), "CANCELLED") == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking can no longer be cancelled.");
        }
        booking.setStatus("CANCELLED"); // reflect the committed change in the response DTO

        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        saveSlotOrConflict(slot);

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
        // 404 for no such visible booking; 409 if it exists but is no longer BOOKED.
        BookingEntity booking = bookingRepository.findByIdAndArchivedAtIsNull(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));
        if (!"BOOKED".equals(booking.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is no longer active.");
        }

        // Atomic guarded transition so a cancel can't race a complete (or another cancel).
        if (bookingRepository.transitionFromBooked(bookingId, "CANCELLED") == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is no longer active.");
        }
        booking.setStatus("CANCELLED");

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());
        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        saveSlotOrConflict(slot);

        UserEntity user = booking.getUserId() != null
                ? userRepository.findById(booking.getUserId()).orElse(null)
                : null;
        return toResponse(booking, slot, user);
    }

    @Transactional
    public BookingResponse adminCompleteBooking(Long bookingId) {
        // 404 for no such visible booking; 409 if it exists but is no longer BOOKED.
        BookingEntity booking = bookingRepository.findByIdAndArchivedAtIsNull(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));
        if (!"BOOKED".equals(booking.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is no longer active.");
        }
        // A still-pending booking was never confirmed (no client email, deposit unverified), so it
        // must not be completable — otherwise Pending → Completed would fire the review email for a
        // client who was never told their booking went through.
        if (booking.getConfirmedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Confirm this booking before completing it.");
        }

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());
        // Can't complete an appointment that hasn't ended yet. Completion fires the review email
        // and can free the slot for rebooking, so gate it on the slot end having passed in the
        // salon's wall-clock (stored as UTC) — never Instant.now(), which would be 8h behind SGT.
        if (slot.getEndTime() == null
                || slot.getEndTime().isAfter(BookingWindow.currentBusinessWallClockUtc())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This appointment can only be completed after it has ended.");
        }

        if (bookingRepository.transitionFromBooked(bookingId, "COMPLETED") == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is no longer active.");
        }
        booking.setStatus("COMPLETED");

        // A COMPLETED booking no longer holds a BOOKED seat, so drop the picked slot's display
        // counter. Persist it in this same transaction via saveSlotOrConflict: if a racing cancel
        // touched the slot, the optimistic-version conflict surfaces as a retryable 409 and rolls
        // the COMPLETED transition back too, so the counter and status never diverge. No scheduling
        // lock — completion only removes concurrency, so a stale guard can never admit an unsafe booking.
        slot.setBookedCount(Math.max(0, slot.getBookedCount() - 1));
        saveSlotOrConflict(slot);

        UserEntity user = booking.getUserId() != null
                ? userRepository.findById(booking.getUserId()).orElse(null)
                : null;
        return toResponse(booking, slot, user);
    }

    // Outcome of an admin confirm. newlyConfirmed is true only when this call won the atomic
    // markConfirmed claim, so the controller sends the client confirmation email exactly on the
    // first confirm and skips it on retries/duplicate requests.
    public record ConfirmResult(BookingResponse booking, boolean newlyConfirmed) {
    }

    // Admin confirms a pending booking (after seeing the deposit): stamps confirmed_at and lets
    // the client confirmation email go out. The booking already holds its BOOKED seat, so nothing
    // touches capacity here. Idempotent under retries — a second confirm returns the booking with
    // newlyConfirmed = false and sends no further email.
    @Transactional
    public ConfirmResult adminConfirmBooking(Long bookingId) {
        // 404 for no such visible booking; 409 if it exists but is no longer BOOKED.
        BookingEntity booking = bookingRepository.findByIdAndArchivedAtIsNull(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));
        if (!"BOOKED".equals(booking.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is no longer active.");
        }

        Instant now = Instant.now();
        boolean newlyConfirmed;
        if (bookingRepository.markConfirmed(bookingId, now) == 1) {
            booking.setConfirmedAt(now);
            newlyConfirmed = true;
        } else {
            // markConfirmed changed 0 rows AND its clearAutomatically detached the row we first
            // read, so 0 doesn't by itself mean "already confirmed" — a cancel/archival could have
            // raced in between. Reload and revalidate to tell a legitimate resend (still BOOKED +
            // confirmed) from a booking that slipped out of BOOKED.
            booking = bookingRepository.findByIdAndArchivedAtIsNull(bookingId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Active booking not found."));
            if (!"BOOKED".equals(booking.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking is no longer active.");
            }
            if (booking.getConfirmedAt() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This booking could not be confirmed. Please retry.");
            }
            newlyConfirmed = false;
        }

        SlotEntity slot = loadSlotOrThrow(booking.getSlotId());
        UserEntity user = booking.getUserId() != null
                ? userRepository.findById(booking.getUserId()).orElse(null)
                : null;
        return new ConfirmResult(toResponse(booking, slot, user), newlyConfirmed);
    }

    // Explicit admin resend of the client confirmation email for an already-confirmed booking
    // (recovers a lost/failed first send). At-least-once by design: the controller always sends.
    @Transactional(readOnly = true)
    public BookingResponse resendConfirmation(Long bookingId) {
        BookingEntity booking = bookingRepository.findByIdAndArchivedAtIsNull(bookingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Active booking not found."));
        if (!"BOOKED".equals(booking.getStatus()) || booking.getConfirmedAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking isn't confirmed yet.");
        }
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

    // Persist a bookedCount change (seat taken or released) with its version check firing now,
    // so a concurrent cancel/booking optimistic-lock conflict surfaces here as a retryable 409
    // instead of escaping at transaction commit as a 500 (which would also silently roll back
    // the booking's status transition).
    private void saveSlotOrConflict(SlotEntity slot) {
        try {
            slotRepository.saveAndFlush(slot);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Slot was modified concurrently. Please try again.");
        }
    }

    // The instant this booking's occupied interval ends: start + durationMin, or the slot's own end
    // for legacy rows with no positive duration. Mirrors OccupiedInterval.end() for the row being
    // created (which isn't yet persisted, so it isn't returned by the occupied-interval query).
    private static Instant occupiedEnd(Instant start, Instant slotEnd, Integer durationMin) {
        return (durationMin != null && durationMin > 0)
                ? start.plus(Duration.ofMinutes(durationMin))
                : slotEnd;
    }

    // Active BOOKED appointments that could overlap a window ending at windowEnd, mapped to
    // SchedulingGuard inputs. The guard clips to the query window, so loading a superset
    // (everything starting before windowEnd) is correct — earlier appointments that already ended
    // contribute nothing.
    private List<SchedulingGuard.Appointment> activeAppointmentsBefore(Instant windowEnd) {
        return bookingRepository.findActiveOccupiedIntervalsBefore(windowEnd).stream()
                .map(iv -> new SchedulingGuard.Appointment(iv.start(), iv.end(), iv.bookingId()))
                .toList();
    }

    // Active capacity tiles covering [windowStart, windowEnd), mapped to SchedulingGuard inputs.
    private List<SchedulingGuard.CapacityTile> activeCapacityTiles(Instant windowStart, Instant windowEnd) {
        return slotRepository.findActiveSlotsOverlapping(windowStart, windowEnd).stream()
                .map(s -> new SchedulingGuard.CapacityTile(s.getStartTime(), s.getEndTime(),
                        s.getCapacity(), s.getId()))
                .toList();
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
                booking.getConfirmedAt(),
                booking.getCreatedAt()
        );
    }
}
