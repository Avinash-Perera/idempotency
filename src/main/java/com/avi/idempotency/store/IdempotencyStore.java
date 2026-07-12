package com.avi.idempotency.store;

import com.avi.idempotency.model.IdempotencyRecord;

import java.util.Optional;

/**
 * Strategy interface for idempotency record persistence.
 *
 * <p>Implementations must guarantee that {@link #saveIfAbsent(IdempotencyRecord)} is
 * <strong>atomic</strong> — if two threads/processes call this method concurrently
 * with the same fingerprint only one should succeed and the other should receive
 * {@code false}, ensuring exactly one request proceeds to execution.</p>
 *
 * <p>Built-in implementations:</p>
 * <ul>
 *   <li>{@link InMemoryIdempotencyStore} – thread-safe LRU-evicting in-memory store,
 *       suitable for single-node deployments and testing.</li>
 *   <li>{@link RedisIdempotencyStore} – distributed store backed by Redis
 *       {@code SET NX EX} for cluster-safe atomic locking.</li>
 * </ul>
 */
public interface IdempotencyStore {

    /**
     * Retrieves an existing record by its fingerprint.
     *
     * @param fingerprint the SHA-256 request fingerprint
     * @return an {@link Optional} containing the record, or empty if absent
     */
    Optional<IdempotencyRecord> get(String fingerprint);

    /**
     * Atomically saves the record only if no record already exists for the fingerprint.
     *
     * @param record the record to save
     * @return {@code true} if the record was saved; {@code false} if one already existed
     */
    boolean saveIfAbsent(IdempotencyRecord record);

    /**
     * Overwrites an existing record (used to transition {@code IN_PROGRESS → COMPLETED}).
     *
     * @param record the updated record
     */
    void update(IdempotencyRecord record);

    /**
     * Deletes the record, releasing any lock so the client can safely retry.
     *
     * @param fingerprint the SHA-256 request fingerprint
     */
    void delete(String fingerprint);
}
