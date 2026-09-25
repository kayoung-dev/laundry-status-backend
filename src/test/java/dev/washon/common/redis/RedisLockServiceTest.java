package dev.washon.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.washon.TestcontainersConfiguration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RedisLockServiceTest {

    @Autowired RedisLockService locks;
    @Autowired StringRedisTemplate redis;

    @Test
    void releasesTheLockAfterTheCriticalSection() {
        String result = locks.withLock("test:release", () -> {
            assertThat(redis.hasKey("lock:test:release")).isTrue();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(redis.hasKey("lock:test:release")).isFalse();
    }

    @Test
    void releasesTheLockWhenTheCriticalSectionThrows() {
        assertThatThrownBy(() -> locks.withLock("test:throws", () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(redis.hasKey("lock:test:throws")).isFalse();
    }

    @Test
    void doesNotDeleteALockNowOwnedBySomeoneElse() {
        // Our TTL expires mid-section and another worker takes the lock; our
        // release must leave their lock alone (compare-and-delete).
        locks.withLock("test:stolen", Duration.ofMillis(50), Duration.ofSeconds(1), () -> {
            sleep(150);
            redis.opsForValue().set("lock:test:stolen", "other-owner");
            return null;
        });

        assertThat(redis.opsForValue().get("lock:test:stolen")).isEqualTo("other-owner");
        redis.delete("lock:test:stolen");
    }

    @Test
    void timesOutWhenTheLockIsHeld() {
        redis.opsForValue().set("lock:test:held", "someone", Duration.ofSeconds(5));

        assertThatThrownBy(() -> locks.withLock("test:held", Duration.ofSeconds(1), Duration.ofMillis(200), () -> 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Timed out acquiring lock \"lock:test:held\"");
        redis.delete("lock:test:held");
    }

    @Test
    void serializesConcurrentCallers() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        int[] counter = {0};
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                futures.add(pool.submit(() -> locks.withLock("test:concurrent", () -> {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    int read = counter[0];
                    sleep(2);
                    counter[0] = read + 1;
                    inside.decrementAndGet();
                    return null;
                })));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(counter[0]).isEqualTo(40);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
