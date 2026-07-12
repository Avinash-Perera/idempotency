package com.avi.idempotency.store;

import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.IdempotencyStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RedisIdempotencyStore} using Mockito mocks for Redis.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Atomic {@code SET NX EX} behaviour via {@code setIfAbsent}</li>
 *   <li>Successful get / update / delete delegation</li>
 *   <li>Concurrent lock contention (second call returns false)</li>
 *   <li>Graceful handling of deserialisation failure</li>
 * </ul>
 */
@DisplayName("RedisIdempotencyStore")
@ExtendWith(MockitoExtension.class)
class RedisIdempotencyStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisIdempotencyStore store;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new RedisIdempotencyStore(redisTemplate);
    }

    // ── saveIfAbsent ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("saveIfAbsent returns true when Redis SET NX EX succeeds")
    void saveIfAbsent_returnsTrue_whenRedisSetNxSucceeds() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE);

        IdempotencyRecord lock = IdempotencyRecord.inProgress("fp-redis", 3600L);
        boolean result = store.saveIfAbsent(lock);

        assertThat(result).isTrue();
        verify(valueOps).setIfAbsent(eq("idempotency:fp-redis"), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("saveIfAbsent returns false when Redis key already exists")
    void saveIfAbsent_returnsFalse_whenRedisKeyExists() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.FALSE);

        IdempotencyRecord lock = IdempotencyRecord.inProgress("fp-existing", 3600L);
        boolean result = store.saveIfAbsent(lock);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("saveIfAbsent simulates concurrent contention: first wins, second loses")
    void saveIfAbsent_concurrentContention_firstWinsSecondLoses() {
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Boolean.TRUE)   // first call
                .thenReturn(Boolean.FALSE); // second concurrent call

        IdempotencyRecord lock = IdempotencyRecord.inProgress("fp-concurrent", 3600L);
        boolean first  = store.saveIfAbsent(lock);
        boolean second = store.saveIfAbsent(lock);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("get returns empty when key not in Redis")
    void get_returnsEmpty_whenKeyNotFound() {
        when(valueOps.get("idempotency:fp-miss")).thenReturn(null);

        Optional<IdempotencyRecord> result = store.get("fp-miss");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("get returns deserialised record when key exists")
    void get_returnsRecord_whenKeyExists() throws Exception {
        IdempotencyRecord expected = IdempotencyRecord.completed(
                "fp-hit", 200, Map.of("Content-Type", java.util.List.of("application/json")), "{}", 3600L);
        String json = objectMapper.writeValueAsString(expected);

        when(valueOps.get("idempotency:fp-hit")).thenReturn(json);

        Optional<IdempotencyRecord> result = store.get("fp-hit");

        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(result.get().httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("get returns empty when JSON is malformed")
    void get_returnsEmpty_whenJsonMalformed() {
        when(valueOps.get("idempotency:fp-bad")).thenReturn("not-valid-json{{{");

        Optional<IdempotencyRecord> result = store.get("fp-bad");
        assertThat(result).isEmpty();
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update calls Redis SET with TTL")
    void update_callsRedisSet() {
        IdempotencyRecord completed = IdempotencyRecord.completed(
                "fp-update", 201, Map.of(), "{\"id\":1}", 3600L);

        store.update(completed);

        verify(valueOps).set(eq("idempotency:fp-update"), anyString(), any(Duration.class));
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete calls Redis delete on the correct key")
    void delete_callsRedisDelete() {
        store.delete("fp-remove");

        verify(redisTemplate).delete("idempotency:fp-remove");
    }
}
