package com.bookmyshow.booking;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class SeatLock {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public SeatLock(StringRedisTemplate redis, @Value("${booking.lock-ttl:10s}") Duration ttl) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Lock TTL must be positive");
        }
        this.redis = redis;
        this.ttl = ttl;
    }

    public boolean acquire(String showId, String seat, String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(showId, seat), token, ttl));
    }

    public void release(String showId, String seat, String token) {
        redis.execute(RELEASE, List.of(key(showId, seat)), token);
    }

    private String key(String showId, String seat) {
        return "booking:seat:" + showId + ":" + seat;
    }
}
