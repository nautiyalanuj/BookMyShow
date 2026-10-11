package com.bookmyshow.booking;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TicketRepository {
    private final JdbcTemplate jdbc;

    public TicketRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // JdbcTemplate autocommits before returning; the Redis lock is released only afterwards.
    public boolean insert(Ticket ticket) {
        return jdbc.update("""
                INSERT INTO tickets (id, show_id, seat_number, user_id, booked_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (show_id, seat_number) DO NOTHING
                """, ticket.id(), ticket.showId(), ticket.seatNumber(), ticket.userId(),
                Timestamp.from(ticket.bookedAt())) == 1;
    }

    public Optional<Ticket> find(UUID id) {
        return jdbc.query("SELECT * FROM tickets WHERE id = ?", (rs, row) -> new Ticket(
                rs.getObject("id", UUID.class), rs.getString("show_id"), rs.getString("seat_number"),
                rs.getString("user_id"), rs.getTimestamp("booked_at").toInstant()), id)
                .stream().findFirst();
    }
}
