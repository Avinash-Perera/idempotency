package com.avi.idempotency.store;

import com.avi.idempotency.model.IdempotencyRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Redis-backed {@link IdempotencyStore} providing distributed, cluster-safe
 * idempotency guarantees.
 *
 * <h2>Atomic locking</h2>
 * <p>{@link #saveIfAbsent(IdempotencyRecord)} uses Redis {@code SET NX EX}
 * (via {@link org.springframework.data.redis.core.ValueOperations#setIfAbsent})
 * which is a single atomic command.  This means even across multiple JVM
 * instances, exactly one process will acquire the lock.</p>
 *
 * <h2>Serialisation</h2>
 * <p>Records are serialised to JSON using Jackson (with the JavaTimeModule for
 * {@link Instant} support) and stored as Redis strings.</p>
 *
 * <h2>TTL</h2>
 * <p>Each key is stored with a Redis TTL derived from
 * {@link IdempotencyRecord#expiresAt()}, so Redis handles expiry natively
 * without application-level sweepers.</p>
 */
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .configure(com.fasterxml.jackson.databind.MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS, true)
                .build();
    }

    // ── IdempotencyStore ─────────────────────────────────────────────────────

    @Override
    public Optional<IdempotencyRecord> get(String fingerprint) {
        String json = redisTemplate.opsForValue().get(redisKey(fingerprint));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, IdempotencyRecord.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialise idempotency record for fingerprint={}", fingerprint, e);
            return Optional.empty();
        }
    }

    /**
     * Atomically writes the record to Redis using {@code SET NX EX}.
     *
     * @return {@code true} if the key was newly created; {@code false} if it already existed.
     * @throws IllegalStateException if the record cannot be serialised to JSON
     */
    @Override
    public boolean saveIfAbsent(IdempotencyRecord record) {
        String key  = redisKey(record.fingerprint());
        String json = serialise(record); // throws IllegalStateException on failure

        Duration ttl = ttlFor(record);
        Boolean set  = redisTemplate.opsForValue().setIfAbsent(key, json, ttl);
        return Boolean.TRUE.equals(set);
    }

    @Override
    public void update(IdempotencyRecord record) {
        String key  = redisKey(record.fingerprint());
        String json = serialise(record); // throws IllegalStateException on failure

        Duration ttl = ttlFor(record);
        redisTemplate.opsForValue().set(key, json, ttl);
    }

    @Override
    public void delete(String fingerprint) {
        redisTemplate.delete(redisKey(fingerprint));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String redisKey(String fingerprint) {
        return KEY_PREFIX + fingerprint;
    }

    /**
     * Serialises the record to JSON.
     *
     * @throws IllegalStateException if serialisation fails — callers must not swallow this;
     *         a serialisation failure means the lock was never written to Redis, so silently
     *         returning {@code false} would allow duplicate processing.
     */
    private String serialise(IdempotencyRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialise idempotency record for fingerprint=" + record.fingerprint()
                    + ". The idempotency lock was NOT written to Redis.", e);
        }
    }

    /**
     * Computes a positive TTL duration from {@link IdempotencyRecord#expiresAt()}.
     * Falls back to 1 second if already expired to avoid Redis rejecting zero/negative TTLs.
     */
    private Duration ttlFor(IdempotencyRecord record) {
        Duration ttl = Duration.between(Instant.now(), record.expiresAt());
        return ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(1) : ttl;
    }
}
