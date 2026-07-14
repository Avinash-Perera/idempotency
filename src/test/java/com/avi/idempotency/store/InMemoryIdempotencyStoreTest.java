package com.avi.idempotency.store;

import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.IdempotencyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InMemoryIdempotencyStore} covering:
 * <ul>
 *   <li>LRU eviction when capacity is exceeded</li>
 *   <li>TTL-based expiry</li>
 *   <li>Atomic {@code saveIfAbsent} under concurrency</li>
 *   <li>State transitions (IN_PROGRESS → COMPLETED)</li>
 * </ul>
 */
@DisplayName("InMemoryIdempotencyStore")
class InMemoryIdempotencyStoreTest {

    private InMemoryIdempotencyStore store;

    @BeforeEach
    void setUp() {
        // Small capacity to make LRU eviction testable
        IdempotencyConfig config = new IdempotencyConfig("Idempotency-Key", 3600L, 3, 2097152, 2, java.util.Set.of(), false);
        store = new InMemoryIdempotencyStore(config);
    }

    // ── Basic CRUD ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should store and retrieve a record")
    void shouldStoreAndRetrieve() {
        IdempotencyRecord record = IdempotencyRecord.inProgress("fp1", 3600L);
        store.saveIfAbsent(record);

        Optional<IdempotencyRecord> found = store.get("fp1");

        assertThat(found).isPresent();
        assertThat(found.get().fingerprint()).isEqualTo("fp1");
        assertThat(found.get().status()).isEqualTo(IdempotencyStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("should return empty for missing fingerprint")
    void shouldReturnEmptyForMissing() {
        assertThat(store.get("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("should delete a record")
    void shouldDeleteRecord() {
        store.saveIfAbsent(IdempotencyRecord.inProgress("fp-delete", 3600L));
        store.delete("fp-delete");
        assertThat(store.get("fp-delete")).isEmpty();
    }

    @Test
    @DisplayName("should update a record from IN_PROGRESS to COMPLETED")
    void shouldUpdateRecord() {
        store.saveIfAbsent(IdempotencyRecord.inProgress("fp-update", 3600L));

        IdempotencyRecord completed = IdempotencyRecord.completed(
                "fp-update", 200, Map.of(), "{}", 3600L);
        store.update(completed);

        Optional<IdempotencyRecord> found = store.get("fp-update");
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(found.get().body()).isEqualTo("{}");
    }

    // ── LRU Eviction ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("should evict least-recently-used entry when capacity is exceeded")
    void shouldEvictLruWhenCapacityExceeded() {
        // Fill up to capacity=3
        store.saveIfAbsent(IdempotencyRecord.inProgress("a", 3600L));
        store.saveIfAbsent(IdempotencyRecord.inProgress("b", 3600L));
        store.saveIfAbsent(IdempotencyRecord.inProgress("c", 3600L));

        // Access "a" to make it recently used; "b" becomes the LRU
        store.get("a");
        store.get("c");

        // Adding "d" should evict "b" (the LRU)
        store.saveIfAbsent(IdempotencyRecord.inProgress("d", 3600L));

        assertThat(store.get("a")).isPresent(); // recently accessed
        assertThat(store.get("c")).isPresent(); // recently accessed
        assertThat(store.get("d")).isPresent(); // just added
        assertThat(store.get("b")).isEmpty();   // evicted as LRU
    }

    @Test
    @DisplayName("should not evict when under capacity")
    void shouldNotEvictWhenUnderCapacity() {
        store.saveIfAbsent(IdempotencyRecord.inProgress("x", 3600L));
        store.saveIfAbsent(IdempotencyRecord.inProgress("y", 3600L));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.get("x")).isPresent();
        assertThat(store.get("y")).isPresent();
    }

    // ── TTL Expiry ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should return empty for an expired record")
    void shouldReturnEmptyForExpiredRecord() {
        // Create a record that is already expired
        IdempotencyRecord expired = new IdempotencyRecord(
                "fp-expired",
                IdempotencyStatus.IN_PROGRESS,
                0,
                Map.of(),
                "",
                Instant.now().minusSeconds(1) // expired 1 second ago
        );
        store.saveIfAbsent(expired);

        assertThat(store.get("fp-expired")).isEmpty();
    }

    @Test
    @DisplayName("should allow saveIfAbsent to overwrite an expired record")
    void shouldOverwriteExpiredRecord() {
        IdempotencyRecord expired = new IdempotencyRecord(
                "fp-overwrite",
                IdempotencyStatus.IN_PROGRESS,
                0,
                Map.of(),
                "",
                Instant.now().minusSeconds(1)
        );
        store.saveIfAbsent(expired);

        IdempotencyRecord fresh = IdempotencyRecord.inProgress("fp-overwrite", 3600L);
        boolean saved = store.saveIfAbsent(fresh);

        assertThat(saved).isTrue();
        assertThat(store.get("fp-overwrite")).isPresent();
    }

    // ── Atomic saveIfAbsent (Concurrency) ─────────────────────────────────────

    @Test
    @DisplayName("should allow only one thread to acquire the lock under concurrency")
    void shouldAllowOnlyOneLockUnderConcurrency() throws InterruptedException {
        int threads = 20;
        AtomicInteger acquiredCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    IdempotencyRecord lock = IdempotencyRecord.inProgress("concurrent-fp", 3600L);
                    if (store.saveIfAbsent(lock)) {
                        acquiredCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // release all threads simultaneously
        doneLatch.await();
        pool.shutdown();

        assertThat(acquiredCount.get())
                .as("Exactly one thread should have acquired the lock")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("saveIfAbsent should return false when record already exists")
    void saveIfAbsentReturnsFalseWhenExists() {
        IdempotencyRecord first = IdempotencyRecord.inProgress("fp-dup", 3600L);
        boolean firstResult = store.saveIfAbsent(first);

        IdempotencyRecord second = IdempotencyRecord.inProgress("fp-dup", 3600L);
        boolean secondResult = store.saveIfAbsent(second);

        assertThat(firstResult).isTrue();
        assertThat(secondResult).isFalse();
    }
}
