package com.bookmyshow.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

class BookingServiceTest {
    private SeatLock locks;
    private TicketRepository repository;
    private BookingService service;

    @BeforeEach
    void setup() {
        locks = mock(SeatLock.class);
        repository = mock(TicketRepository.class);
        service = new BookingService(locks, repository);
    }

    @Test
    void contentionNeverReachesDatabase() {
        assertThatThrownBy(() -> service.book(new BookingRequest("show-1", "a1", "user-1")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
        verifyNoInteractions(repository);
        verify(locks, never()).release(anyString(), anyString(), anyString());
    }

    @Test
    void redisOutageNeverReachesDatabase() {
        when(locks.acquire(anyString(), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        assertThatThrownBy(() -> service.book(new BookingRequest("show-1", "A1", "user-1")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void databaseConflictStillReleasesLock() {
        when(locks.acquire(anyString(), anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.book(new BookingRequest("show-1", "a1", "user-1")))
                .isInstanceOf(ResponseStatusException.class);
        verify(locks).release(eq("show-1"), eq("A1"), anyString());
    }

    @Test
    void persistenceFailureReleasesLock() {
        when(locks.acquire(anyString(), anyString(), anyString())).thenReturn(true);
        when(repository.insert(any())).thenThrow(new DataAccessResourceFailureException("database down"));
        assertThatThrownBy(() -> service.book(new BookingRequest("show-1", "A1", "user-1")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(locks).release(eq("show-1"), eq("A1"), anyString());
    }

    @Test
    void cleanupFailureDoesNotHideCommittedBooking() {
        when(locks.acquire(anyString(), anyString(), anyString())).thenReturn(true);
        when(repository.insert(any())).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(locks).release(anyString(), anyString(), anyString());
        Ticket ticket = service.book(new BookingRequest("show-1", "a1", "user-1"));
        assertThat(ticket.seatNumber()).isEqualTo("A1");
        var order = inOrder(repository, locks);
        order.verify(locks).acquire(eq("show-1"), eq("A1"), anyString());
        order.verify(repository).insert(ticket);
        order.verify(locks).release(eq("show-1"), eq("A1"), anyString());
    }
}
