package com.bookmyshow.booking;

import java.time.Instant;
import java.util.UUID;

public record Ticket(UUID id, String showId, String seatNumber, String userId, Instant bookedAt) {
}
