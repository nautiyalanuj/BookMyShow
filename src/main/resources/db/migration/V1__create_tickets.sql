CREATE TABLE tickets (
    id UUID PRIMARY KEY,
    show_id VARCHAR(64) NOT NULL,
    seat_number VARCHAR(10) NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    booked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_show_seat UNIQUE (show_id, seat_number),
    CONSTRAINT canonical_seat CHECK (seat_number = upper(seat_number))
);
