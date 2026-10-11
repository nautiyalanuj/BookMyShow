# Ticket Booking Service

Sample Java 21 / Spring Boot API that coordinates seat bookings with Redis and durably stores tickets in PostgreSQL. Seat identity is `(showId, seatNumber)`; seat numbers are normalized to uppercase, while show IDs are case-sensitive.

## Run

Install Docker with Compose, then:

```sh
docker compose up --build
```

The API runs at `http://localhost:8080`. PostgreSQL data persists in a Docker volume. The supplied credentials are for local development.

Alternatively, start only the dependencies and run Java locally (Java 21 and Maven 3.8+):

```sh
docker compose up -d postgres redis
mvn spring-boot:run
```

## Book a ticket

```sh
curl -i -X POST http://localhost:8080/api/bookings \
  -H 'Content-Type: application/json' \
  -d '{"showId":"show-101","seatNumber":"A1","userId":"user-42"}'
```

Successful requests return **201 Created**, a `Location` header, and the persisted ticket:

```json
{
  "id": "0c2e7348-50b2-4ccd-a417-ed2c30830c56",
  "showId": "show-101",
  "seatNumber": "A1",
  "userId": "user-42",
  "bookedAt": "2026-10-11T10:00:00Z"
}
```

Retrieve the ticket with `GET /api/bookings/{id}`. Responses use 400 for invalid inputs, 404 for unknown tickets, 409 for an already booked or currently locked seat, and 503 for unavailable booking dependencies. Show IDs allow 1–64 letters, digits, underscores or hyphens; seats allow 1–10 letters or digits; user IDs must be nonblank and at most 128 characters.

## Concurrency and durability

1. Acquire a Redis key per show and seat using atomic `SET NX` with a 10-second expiration and a random ownership token. Contending requests fail quickly with 409; unrelated seats use separate keys.
2. Insert the ticket synchronously in PostgreSQL. A unique `(show_id, seat_number)` constraint and `ON CONFLICT DO NOTHING` guarantee at most one persisted booking even when a lease expires or Redis restarts. Flyway creates the schema on startup.
3. After the database write commits, release the lock using a Lua token comparison and deletion. An expired owner cannot delete a later owner's lock. Crashed processes leave only an expiring lease. Cleanup failures do not hide successful bookings.

A Redis lease reduces contention; PostgreSQL is the final correctness boundary. It cannot eliminate all contention during outages or lease expiration. The API confirms bookings only after persistence completes, so no background queue can lose an acknowledged booking. Persistence is immediate rather than asynchronous eventual storage.

This sample accepts caller-supplied shows/seats and user IDs; it has no seat inventory, authentication, payments, cancellation, or idempotency key. A lost HTTP response after commit can be followed by a 409 on retry. Add those features before using it as a production booking system.

Configuration: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, and `BOOKING_LOCK_TTL` (for example `10s`). Database statements time out after 5 seconds; Redis commands after 2 seconds.

## Test

```sh
mvn verify
```

Docker must be available for the integration suite. Tests start isolated PostgreSQL and Redis containers and check 24 concurrent HTTP requests for one seat, independent seats/shows, validation, ticket retrieval, database uniqueness, lease expiry, and ownership-safe release. Unit tests cover contention and dependency/cleanup failures. GitHub Actions runs the same suite.

For only unit tests without Docker:

```sh
mvn -Dtest=BookingServiceTest test
```

Design references: [Redis distributed locks](https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/) and [PostgreSQL INSERT / ON CONFLICT](https://www.postgresql.org/docs/16/sql-insert.html).
