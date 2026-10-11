package com.bookmyshow.booking;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private final SeatLock locks;
    private final TicketRepository tickets;

    public BookingService(SeatLock locks, TicketRepository tickets) {
        this.locks = locks;
        this.tickets = tickets;
    }

    public Ticket book(BookingRequest request) {
        String seat = request.seatNumber().toUpperCase(Locale.ROOT);
        String token = UUID.randomUUID().toString();
        if (!locks.acquire(request.showId(), seat, token)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat is being booked; retry later");
        }
        try {
            Ticket ticket = new Ticket(UUID.randomUUID(), request.showId(), seat, request.userId(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            if (!tickets.insert(ticket)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat is already booked");
            }
            return ticket;
        } finally {
            try {
                locks.release(request.showId(), seat, token);
            } catch (RuntimeException e) {
                // A cleanup outage must not turn a committed booking into a reported failure.
                // The lease expires automatically; PostgreSQL still prevents duplicate bookings.
                log.warn("Seat lock cleanup failed; lease will expire", e);
            }
        }
    }

    public Ticket find(UUID id) {
        return tickets.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Ticket not found"));
    }
}
