package com.avi.idempotency.store;

import com.avi.idempotency.config.IdempotencyConfig;
import com.avi.idempotency.model.IdempotencyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe, in-memory {@link IdempotencyStore} with a custom LRU eviction
 * algorithm and TTL-based expiry.
 *
 * <h2>LRU Design</h2>
 * <p>An access-ordered {@link LinkedHashMap} (capacity-bounded) provides O(1)
 * get/put with automatic LRU eviction when {@code maxCapacity} is exceeded.
 * A {@link ReentrantReadWriteLock} allows multiple concurrent readers while
 * serialising writers.</p>
 *
 * <h2>Atomic {@code saveIfAbsent}</h2>
 * <p>Write operations acquire the write lock exclusively, guaranteeing that
 * "check-then-act" is atomic within a single JVM process.</p>
 *
 * <h2>TTL Sweep</h2>
 * <p>A daemon {@link ScheduledExecutorService} sweeps expired entries every
 * 60 seconds so memory usage is bounded even when capacity is not reached.</p>
 *
 * <p><strong>Note:</strong> this store is not suitable for multi-node deployments;
 * use {@link RedisIdempotencyStore} for horizontally-scaled services.</p>
 */
public class InMemoryIdempotencyStore implements IdempotencyStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);
    private static final long SWEEP_INTERVAL_SECONDS = 60L;

    /** The underlying LRU map guarded by {@link #lock}. */
    private final Map<String, IdempotencyRecord> lruCache;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private final ScheduledExecutorService sweeper;

    /**
     * Constructs the store with the supplied configuration.
     *
     * @param config library configuration driving {@code maxCapacity}
     */
    public InMemoryIdempotencyStore(IdempotencyConfig config) {
        int capacity = config.maxCapacity();
        // Access-ordered LinkedHashMap with LRU eviction on overflow
        this.lruCache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, IdempotencyRecord> eldest) {
                boolean shouldEvict = size() > capacity;
                if (shouldEvict) {
                    log.debug("LRU eviction: removing fingerprint={}", eldest.getKey());
                }
                return shouldEvict;
            }
        };

        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-sweeper");
            t.setDaemon(true);
            return t;
        });
        this.sweeper.scheduleAtFixedRate(this::sweepExpired,
                SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    // ── IdempotencyStore ─────────────────────────────────────────────────────

    @Override
    public Optional<IdempotencyRecord> get(String fingerprint) {
        writeLock.lock();
        try {
            IdempotencyRecord record = lruCache.get(fingerprint);
            if (record == null || record.isExpired()) {
                return Optional.empty();
            }
            return Optional.of(record);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Atomically saves the record only if no entry currently exists for the fingerprint.
     * Expired records are treated as absent (overwritten).
     */
    @Override
    public boolean saveIfAbsent(IdempotencyRecord record) {
        writeLock.lock();
        try {
            IdempotencyRecord existing = lruCache.get(record.fingerprint());
            if (existing != null && !existing.isExpired()) {
                return false; // already present and valid → do not overwrite
            }
            lruCache.put(record.fingerprint(), record);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void update(IdempotencyRecord record) {
        writeLock.lock();
        try {
            lruCache.put(record.fingerprint(), record);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void delete(String fingerprint) {
        writeLock.lock();
        try {
            lruCache.remove(fingerprint);
        } finally {
            writeLock.unlock();
        }
    }

    // ── AutoCloseable ────────────────────────────────────────────────────────

    @Override
    public void close() {
        sweeper.shutdownNow();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Removes all entries whose TTL has elapsed. */
    private void sweepExpired() {
        writeLock.lock();
        try {
            int before = lruCache.size();
            lruCache.entrySet().removeIf(e -> e.getValue().isExpired());
            int removed = before - lruCache.size();
            if (removed > 0) {
                log.debug("TTL sweep: removed {} expired idempotency records", removed);
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns the current number of entries (for testing / monitoring).
     */
    public int size() {
        writeLock.lock();
        try {
            return lruCache.size();
        } finally {
            writeLock.unlock();
        }
    }

}
