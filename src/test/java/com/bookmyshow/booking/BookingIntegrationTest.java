package com.bookmyshow.booking;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class BookingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TicketRepository tickets;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM tickets");
    }

    @Test
    void simultaneousRequestsCreateExactlyOneTicket() throws Exception {
        String show = "concurrent-" + UUID.randomUUID();
        int clients = 24;
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(clients)) {
            var results = new ArrayList<Future<Integer>>();
            for (int i = 0; i < clients; i++) {
                String user = "user-" + i;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return http.postForEntity("/api/bookings", new BookingRequest(show, "a1", user), String.class)
                            .getStatusCode().value();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var result : results) statuses.add(result.get(30, TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE show_id = ?", Long.class, show))
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentDatabaseWritesRemainUniqueWithoutRedis() throws Exception {
        String show = "db-race-" + UUID.randomUUID();
        int clients = 12;
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(clients)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < clients; i++) {
                String user = "user-" + i;
                results.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return tickets.insert(new Ticket(UUID.randomUUID(), show, "A1", user, Instant.now()));
                }));
            }
            start.countDown();
            int successes = 0;
            for (var result : results) if (result.get(30, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE show_id = ?", Long.class, show))
                    .isEqualTo(1);
        }
    }

    @Test
    void sameSeatInDifferentShowsAndDifferentSeatsCanBeBooked() {
        String show = "independent-" + UUID.randomUUID();
        for (var request : new BookingRequest[] {
                new BookingRequest(show, "A1", "u1"), new BookingRequest(show, "A2", "u2"),
                new BookingRequest(show + "-2", "A1", "u3")}) {
            var response = http.postForEntity("/api/bookings", request, Ticket.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(http.getForEntity(response.getHeaders().getLocation(), Ticket.class).getBody())
                    .isEqualTo(response.getBody());
        }
    }

    @Test
    void invalidRequestAndUnknownTicket() {
        assertThat(http.postForEntity("/api/bookings", new BookingRequest("show", "bad:seat", ""), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/bookings/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void expiredOwnerCannotDeleteNewOwnersLock() throws Exception {
        String show = "lease-" + UUID.randomUUID();
        SeatLock shortLease = new SeatLock(redis, Duration.ofMillis(100));
        assertThat(shortLease.acquire(show, "A1", "old")).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey("booking:seat:" + show + ":A1"))) {
            if (System.nanoTime() > deadline) fail("Lease did not expire");
            Thread.sleep(20);
        }
        SeatLock newLease = new SeatLock(redis, Duration.ofSeconds(10));
        assertThat(newLease.acquire(show, "A1", "new")).isTrue();
        shortLease.release(show, "A1", "old");
        assertThat(newLease.acquire(show, "A1", "third")).isFalse();
        newLease.release(show, "A1", "new");
    }

    @Test
    void databaseProtectsSeatEvenWhenRedisLeaseIsGone() {
        String show = "persisted-" + UUID.randomUUID();
        assertThat(tickets.insert(new Ticket(UUID.randomUUID(), show, "A1", "first", Instant.now()))).isTrue();
        assertThat(http.postForEntity("/api/bookings", new BookingRequest(show, "A1", "second"), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE show_id = ?", Long.class, show))
                .isEqualTo(1);
    }
}
