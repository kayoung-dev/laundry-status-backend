package dev.washon.common.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Atomic Redis token-bucket rate limiter, ported from
 * {@code apps/api/src/redis/redis.service.ts#consumeToken}: {@code capacity}
 * tokens max, refilling one token every {@code refillInterval}.
 *
 * <p>This is a separate concern from {@link RedisLockService} (counting, not
 * mutual exclusion) but shares its execution pattern: a Lua script run via
 * {@link StringRedisTemplate} + {@link DefaultRedisScript} so keys/args are
 * plain strings rather than JDK-serialized (the default {@code RedisTemplate}
 * would break the script's string comparisons/arithmetic).
 */
@Service
public class RateLimiterService {

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_interval_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local data = redis.call("HMGET", key, "tokens", "ts")
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])

            if tokens == nil then
              tokens = capacity
              ts = now
            end

            local elapsed = now - ts
            if elapsed < 0 then
              elapsed = 0
            end

            local refilled = elapsed / refill_interval_ms
            tokens = math.min(capacity, tokens + refilled)

            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call("HMSET", key, "tokens", tostring(tokens), "ts", tostring(now))
            redis.call("PEXPIRE", key, 60000)

            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;

    public RateLimiterService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Attempts to consume one token from the bucket identified by
     * {@code key}. Returns {@code true} if the request is allowed,
     * {@code false} if it should be rejected (e.g. with 429).
     */
    public boolean tryConsume(String key, int capacity, Duration refillInterval) {
        Long allowed = redis.execute(TOKEN_BUCKET_SCRIPT, List.of("ratelimit:" + key),
                String.valueOf(capacity), String.valueOf(refillInterval.toMillis()),
                String.valueOf(System.currentTimeMillis()));
        return allowed != null && allowed == 1L;
    }
}
