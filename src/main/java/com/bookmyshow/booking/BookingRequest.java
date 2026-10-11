package com.bookmyshow.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BookingRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String showId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,10}") String seatNumber,
        @NotBlank @Size(max = 128) String userId) {
}
