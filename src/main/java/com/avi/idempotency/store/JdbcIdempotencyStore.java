package com.avi.idempotency.store;

import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.IdempotencyStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * A JDBC-backed {@link IdempotencyStore} for enterprise financial systems.
 *
 * <h2>Transactional Safety</h2>
 * <p>Unlike Redis, this store allows idempotency locks to participate in the
 * same SQL {@code @Transactional} boundary as your business logic. If a payment
 * rollback occurs, the idempotency lock is rolled back as well, completely
 * preventing double-charges in failure scenarios.</p>
 *
 * <h2>Required Schema</h2>
 * <pre>{@code
 * CREATE TABLE idempotency_records (
 *     fingerprint VARCHAR(255) PRIMARY KEY,
 *     status VARCHAR(50) NOT NULL,
 *     http_status INT NOT NULL,
 *     headers TEXT NOT NULL,
 *     body TEXT NOT NULL,
 *     expires_at TIMESTAMP NOT NULL
 * );
 * 
 * -- CRITICAL: You MUST create an index on expires_at to prevent 
 * -- full table scans during the background cleanup task!
 * CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at);
 * }</pre>
 */
public class JdbcIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcIdempotencyStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService cleanupExecutor;

    public JdbcIdempotencyStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-db-cleanup");
            t.setDaemon(true); // Don't block JVM shutdown
            return t;
        });
    }

    @PostConstruct
    public void startCleanupTask() {
        // Run cleanup every 1 hour to prevent the table from growing infinitely
        cleanupExecutor.scheduleAtFixedRate(this::deleteExpiredRecords, 1, 1, TimeUnit.HOURS);
    }

    @PreDestroy
    public void stopCleanupTask() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void deleteExpiredRecords() {
        try {
            int deleted = jdbcTemplate.update(
                    "DELETE FROM idempotency_records WHERE expires_at < ?",
                    Timestamp.from(Instant.now())
            );
            if (deleted > 0) {
                log.debug("Cleaned up {} expired idempotency records from the database", deleted);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up expired idempotency records", e);
        }
    }

    private RowMapper<IdempotencyRecord> rowMapper() {
        return (rs, rowNum) -> {
            try {
                Map<String, List<String>> headers = objectMapper.readValue(
                        rs.getString("headers"), new TypeReference<>() {}
                );
                return new IdempotencyRecord(
                        rs.getString("fingerprint"),
                        IdempotencyStatus.valueOf(rs.getString("status")),
                        rs.getInt("http_status"),
                        headers,
                        rs.getString("body"),
                        rs.getTimestamp("expires_at").toInstant()
                );
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to deserialise headers", e);
            }
        };
    }

    @Override
    public Optional<IdempotencyRecord> get(String fingerprint) {
        // Single query: fetch only non-expired records.
        // Bulk deletion of expired rows is handled by the scheduled cleanup task
        // (every hour), which is far more efficient than a per-request DELETE.
        try {
            IdempotencyRecord record = jdbcTemplate.queryForObject(
                    "SELECT * FROM idempotency_records WHERE fingerprint = ? AND expires_at >= ?",
                    rowMapper(),
                    fingerprint,
                    Timestamp.from(Instant.now())
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean saveIfAbsent(IdempotencyRecord record) {
        try {
            String headersJson = objectMapper.writeValueAsString(record.headers());
            int rows = jdbcTemplate.update(
                    "INSERT INTO idempotency_records (fingerprint, status, http_status, headers, body, expires_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    record.fingerprint(),
                    record.status().name(),
                    record.httpStatus(),
                    headersJson,
                    record.body(),
                    Timestamp.from(record.expiresAt())
            );
            return rows > 0;
        } catch (DuplicateKeyException e) {
            // Another transaction acquired the lock first
            return false;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise headers for fingerprint={}", record.fingerprint(), e);
            return false;
        }
    }

    @Override
    public void update(IdempotencyRecord record) {
        try {
            String headersJson = objectMapper.writeValueAsString(record.headers());
            jdbcTemplate.update(
                    "UPDATE idempotency_records SET status = ?, http_status = ?, headers = ?, body = ?, expires_at = ? " +
                            "WHERE fingerprint = ?",
                    record.status().name(),
                    record.httpStatus(),
                    headersJson,
                    record.body(),
                    Timestamp.from(record.expiresAt()),
                    record.fingerprint()
            );
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise headers for fingerprint={}", record.fingerprint(), e);
        }
    }

    @Override
    public void delete(String fingerprint) {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE fingerprint = ?", fingerprint);
    }
}
