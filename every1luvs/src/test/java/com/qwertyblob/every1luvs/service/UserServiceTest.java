package com.qwertyblob.every1luvs.service;

import com.qwertyblob.every1luvs.dto.UserResponse;
import com.qwertyblob.every1luvs.entity.BookingEntity;
import com.qwertyblob.every1luvs.entity.SlotEntity;
import com.qwertyblob.every1luvs.entity.UserEntity;
import com.qwertyblob.every1luvs.repository.BookingRepository;
import com.qwertyblob.every1luvs.repository.SchedulingLockRepository;
import com.qwertyblob.every1luvs.repository.SlotRepository;
import com.qwertyblob.every1luvs.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock BookingRepository bookingRepository;
    @Mock SlotRepository slotRepository;
    @Mock SchedulingLockRepository schedulingLockRepository;

    @InjectMocks UserService userService;

    private static UserEntity user(Long id, String name, String email, String role) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    private static BookingEntity booking(Long slotId, String status) {
        BookingEntity booking = new BookingEntity();
        booking.setSlotId(slotId);
        booking.setStatus(status);
        return booking;
    }

    private static SlotEntity slot(Long id, int bookedCount) {
        SlotEntity slot = new SlotEntity();
        slot.setId(id);
        slot.setBookedCount(bookedCount);
        return slot;
    }

    @Test
    void getByEmail_existingUser_returnsUserResponse() {
        when(userRepository.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(user(1L, "Alice", "alice@example.com", "USER")));

        assertThat(userService.getByEmail("alice@example.com"))
                .isEqualTo(new UserResponse(1L, "Alice", "alice@example.com", "USER"));
    }

    @Test
    void getByEmail_missingUser_throwsUnauthorized() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail("ghost@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(rse.getReason()).isEqualTo("User no longer exists.");
                });
    }

    @Test
    void listAll_mapsAllUsersToResponses() {
        Pageable pageable = PageRequest.of(0, 50);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(
                user(1L, "Alice", "alice@example.com", "USER"),
                user(2L, "Bob", "bob@example.com", "ADMIN")
        ), pageable, 2));

        Page<UserResponse> result = userService.listAll(pageable);

        assertThat(result.getContent()).containsExactly(
                new UserResponse(1L, "Alice", "alice@example.com", "USER"),
                new UserResponse(2L, "Bob", "bob@example.com", "ADMIN")
        );
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    void listAll_emptyRepository_returnsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 50);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

        assertThat(userService.listAll(pageable)).isEmpty();
    }

    // ─── deleteAccount ─────────────────────────────────────────────────────────────

    @Test
    void deleteAccount_releasesActiveSeatsThenDeletesBookingsAndUser() {
        UserEntity alice = user(1L, "Alice", "alice@example.com", "USER");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(alice)); // re-read under the lock
        // Two active bookings on slot 10, one on slot 20, plus a cancelled one that holds no seat.
        when(bookingRepository.findByUserIdForUpdate(1L)).thenReturn(List.of(
                booking(10L, "BOOKED"),
                booking(10L, "BOOKED"),
                booking(20L, "BOOKED"),
                booking(20L, "CANCELLED")
        ));
        when(slotRepository.findById(10L)).thenReturn(Optional.of(slot(10L, 3)));
        when(slotRepository.findById(20L)).thenReturn(Optional.of(slot(20L, 1)));

        userService.deleteAccount("alice@example.com");

        ArgumentCaptor<SlotEntity> savedSlots = ArgumentCaptor.forClass(SlotEntity.class);
        verify(slotRepository, org.mockito.Mockito.times(2)).saveAndFlush(savedSlots.capture());
        assertThat(savedSlots.getAllValues())
                .extracting(SlotEntity::getId, SlotEntity::getBookedCount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(10L, 1), // 3 - 2 active
                        org.assertj.core.groups.Tuple.tuple(20L, 0)  // 1 - 1 active
                );
        verify(bookingRepository).deleteByUserId(1L);
        verify(userRepository).delete(alice);
    }

    @Test
    void deleteAccount_noActiveBookings_skipsSeatReleaseButStillDeletes() {
        UserEntity alice = user(1L, "Alice", "alice@example.com", "USER");
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(alice));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(alice)); // re-read under the lock
        when(bookingRepository.findByUserIdForUpdate(1L)).thenReturn(List.of(
                booking(10L, "CANCELLED"),
                booking(20L, "COMPLETED")
        ));

        userService.deleteAccount("alice@example.com");

        verify(slotRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        verify(bookingRepository).deleteByUserId(1L);
        verify(userRepository).delete(alice);
    }

    @Test
    void deleteAccount_missingUser_throwsUnauthorizedAndDeletesNothing() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteAccount("ghost@example.com"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));

        verifyNoInteractions(bookingRepository, slotRepository);
    }
}
