package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SchedulingLockRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;
    private final SlotRepository slotRepository;
    private final SchedulingLockRepository schedulingLockRepository;

    public UserService(UserRepository userRepository, BookingRepository bookingRepository,
                       SlotRepository slotRepository, SchedulingLockRepository schedulingLockRepository) {
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
        this.slotRepository = slotRepository;
        this.schedulingLockRepository = schedulingLockRepository;
    }

    // Look up the authenticated user's own profile. Treats a missing row as 401 (the account
    // was deleted out from under a still-valid session) rather than 404.
    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists."));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> listAll(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::from);
    }

    // Permanently delete the authenticated user and all of their bookings. Active (BOOKED)
    // bookings are still holding a slot seat, so release those first; otherwise deleting the
    // account would leak capacity on upcoming slots.
    @Transactional
    public void deleteAccount(String email) {
        UserEntity user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists."));

        // Serialize against booking confirmation in the same fixed order it uses (scheduling lock,
        // then the user row). Without this, a confirmation can commit between our booking snapshot
        // and the user delete; the FK cascade would then remove that new booking without releasing
        // its picked-slot seat. Under the lock, a racing confirmation either commits fully before us
        // (its booking is in our snapshot, so its seat is released) or blocks until we delete the
        // user and then fails to find them.
        schedulingLockRepository.acquire();
        // Re-read the user under the lock and use that entity — a concurrent deletion could have
        // removed the row between our findByEmail above and acquiring the lock; treat that as 401.
        user = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists."));

        // Lock the user's booking rows before touching any slot: a concurrent cancel/complete
        // (which take no scheduling lock) would otherwise commit a seat-release between this
        // snapshot and the decrement below and double-count. Locking bookings before slots keeps a
        // consistent lock order with those paths, so no deadlock.
        List<BookingEntity> bookings = bookingRepository.findByUserIdForUpdate(user.getId());

        // Group active bookings per slot so a slot with several of this user's bookings has its
        // bookedCount decremented by the right amount in one update. Cancelled bookings already
        // released their seat, and completed ones are in the past, so neither is touched.
        // saveAndFlush forces the version-checked UPDATE to fire now: the row locks above only cover
        // THIS user's bookings, so a concurrent cancel/complete of another user's booking on a
        // SHARED slot can still bump the slot version. Surface that as a retryable 409 (the whole
        // deletion rolls back cleanly to retry) instead of a commit-time 500.
        try {
            bookings.stream()
                    .filter(booking -> "BOOKED".equals(booking.getStatus()))
                    .collect(Collectors.groupingBy(BookingEntity::getSlotId, Collectors.counting()))
                    .forEach((slotId, seatsHeld) -> slotRepository.findById(slotId).ifPresent(slot -> {
                        slot.setBookedCount(Math.max(0, slot.getBookedCount() - seatsHeld.intValue()));
                        slotRepository.saveAndFlush(slot);
                    }));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Your account could not be deleted due to a concurrent change. Please try again.");
        }

        bookingRepository.deleteByUserId(user.getId());
        userRepository.delete(user);
    }
}
