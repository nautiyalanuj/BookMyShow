package com.bookmyshow.booking;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {
    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Ticket> book(@Valid @RequestBody BookingRequest request) {
        Ticket ticket = service.book(request);
        return ResponseEntity.created(URI.create("/api/bookings/" + ticket.id())).body(ticket);
    }

    @GetMapping("/{id}")
    public Ticket find(@PathVariable UUID id) {
        return service.find(id);
    }
}
