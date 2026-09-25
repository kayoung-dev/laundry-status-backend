package dev.washon.common.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Exclusive Redis lock (SET key token NX PX ttl + Lua compare-and-delete
 * release), ported from {@code apps/api/src/redis/redis.service.ts#withLock}.
 *
 * <p>Uses {@link StringRedisTemplate} on purpose: the default
 * {@code RedisTemplate} JDK-serializes keys/args, so the release script's
 * {@code GET == ARGV[1]} comparison would never match and the lock would only
 * ever be freed by TTL expiry.
 */
@Service
public class RedisLockService {

    /** Lock expiry, a safety net against a crashed holder. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(10);
    /** How long to keep retrying acquisition before giving up. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(15);

    private static final long RETRY_BASE_MS = 8;
    private static final long RETRY_JITTER_MS = 12;

    // Releases a lock only if we still own it: a lock that expired and was
    // re-acquired by another worker must never be deleted by the previous owner.
    private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call("GET", KEYS[1]) == ARGV[1] then
              return redis.call("DEL", KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public <T> T withLock(String key, Supplier<T> fn) {
        return withLock(key, DEFAULT_TTL, DEFAULT_WAIT, fn);
    }

    /**
     * Runs {@code fn} while holding an exclusive lock on {@code key}, so work
     * scoped to one entity (e.g. one machine's state transition) is serialized
     * across concurrent callers. Blocks -- with jittered retries -- until the
     * lock is free rather than failing fast, because callers must not drop
     * updates; throws {@link IllegalStateException} once {@code wait} elapses.
     */
    public <T> T withLock(String key, Duration ttl, Duration wait, Supplier<T> fn) {
        String lockKey = "lock:" + key;
        String token = UUID.randomUUID().toString();
        long deadline = System.currentTimeMillis() + wait.toMillis();

        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, ttl))) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "Timed out acquiring lock \"" + lockKey + "\" after " + wait.toMillis() + "ms");
            }
            try {
                Thread.sleep(RETRY_BASE_MS + ThreadLocalRandom.current().nextLong(RETRY_JITTER_MS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while acquiring lock \"" + lockKey + "\"", e);
            }
        }

        try {
            return fn.get();
        } finally {
            redis.execute(RELEASE_LOCK_SCRIPT, List.of(lockKey), token);
        }
    }
}
