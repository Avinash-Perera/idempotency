package com.avi.idempotency.core;

import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.IdempotencyStatus;
import com.avi.idempotency.model.ProcessResult;
import com.avi.idempotency.store.IdempotencyStore;
import com.avi.idempotency.util.ResponseCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central, transport-agnostic engine that coordinates idempotency check-and-lock
 * logic.  Extracted from the filter and interceptor (Single Responsibility
 * Principle) so the algorithm lives in exactly one place (DRY).
 *
 * <p>Both {@link com.avi.idempotency.filter.IdempotencyFilter} (Mode A) and
 * {@link com.avi.idempotency.interceptor.IdempotencyInterceptor} (Mode B)
 * delegate to this class for all state transitions.</p>
 */
public class IdempotencyProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyProcessor.class);

    /**
     * Looks up the store for an existing record and, if absent, atomically
     * acquires the {@code IN_PROGRESS} lock.
     *
     * <p>State machine:</p>
     * <pre>
     *  ABSENT            → saveIfAbsent(IN_PROGRESS) → {@link ProcessResult.NotFound}
     *  IN_PROGRESS       →                             {@link ProcessResult.InProgress}
     *  COMPLETED (fresh) →                             {@link ProcessResult.Completed}
     *  COMPLETED (stale) → treated as absent           {@link ProcessResult.NotFound}
     * </pre>
     *
     * @param fingerprint unique SHA-256 request fingerprint
     * @param store       the backing store to consult
     * @param ttlSeconds  TTL for the {@code IN_PROGRESS} lock entry
     * @return a {@link ProcessResult} describing the current state
     */
    public ProcessResult checkAndLock(String fingerprint,
                                      IdempotencyStore store,
                                      long ttlSeconds) {

        return store.get(fingerprint).map(existing -> {
            if (existing.status() == IdempotencyStatus.IN_PROGRESS) {
                log.debug("Idempotency: IN_PROGRESS fingerprint={}", fingerprint);
                return (ProcessResult) new ProcessResult.InProgress();
            } else {
                log.debug("Idempotency: COMPLETED HIT fingerprint={}", fingerprint);
                return (ProcessResult) new ProcessResult.Completed(existing);
            }
        }).orElseGet(() -> {
            // Attempt atomic lock acquisition
            IdempotencyRecord lock = IdempotencyRecord.inProgress(fingerprint, ttlSeconds);
            boolean acquired = store.saveIfAbsent(lock);
            if (!acquired) {
                // Race condition: another thread/process just acquired the lock
                log.debug("Idempotency: lost race on fingerprint={}, returning IN_PROGRESS", fingerprint);
                return new ProcessResult.InProgress();
            }
            log.debug("Idempotency: new request, lock acquired fingerprint={}", fingerprint);
            return new ProcessResult.NotFound();
        });
    }

    /**
     * Transitions the record from {@code IN_PROGRESS} to {@code COMPLETED} with
     * the captured response stored for future replay.
     *
     * @param fingerprint    unique request fingerprint
     * @param captured       the response snapshot from {@link ResponseCapture}
     * @param store          the backing store
     * @param ttlSeconds     TTL for the completed cache entry
     */
    public void completeSuccess(String fingerprint,
                                ResponseCapture.CapturedResponse captured,
                                IdempotencyStore store,
                                long ttlSeconds) {

        IdempotencyRecord completed = IdempotencyRecord.completed(
                fingerprint,
                captured.status(),
                captured.headers(),
                captured.body(),
                ttlSeconds
        );
        store.update(completed);
        log.debug("Idempotency: stored COMPLETED fingerprint={} status={}", fingerprint, captured.status());
    }

    /**
     * Deletes the {@code IN_PROGRESS} lock so the client can safely retry.
     * Called on unhandled exceptions or 5xx responses.
     *
     * @param fingerprint unique request fingerprint
     * @param store       the backing store
     */
    public void releaseLock(String fingerprint, IdempotencyStore store) {
        store.delete(fingerprint);
        log.debug("Idempotency: lock released (retry allowed) fingerprint={}", fingerprint);
    }
}
