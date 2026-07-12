package com.avi.idempotency.core;

import com.avi.idempotency.model.IdempotencyRecord;
import com.avi.idempotency.model.IdempotencyStatus;
import com.avi.idempotency.model.ProcessResult;
import com.avi.idempotency.store.IdempotencyStore;
import com.avi.idempotency.util.ResponseCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IdempotencyProcessor} state machine logic.
 *
 * <p>Uses a mocked {@link IdempotencyStore} to verify correct state transitions
 * and delegation without coupling to a real storage backend.</p>
 */
@DisplayName("IdempotencyProcessor")
@ExtendWith(MockitoExtension.class)
class IdempotencyProcessorTest {

    @Mock
    private IdempotencyStore store;

    private IdempotencyProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new IdempotencyProcessor();
    }

    // ── checkAndLock ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns NotFound and acquires lock when fingerprint is absent")
    void returnsNotFound_whenFingerprintAbsent() {
        when(store.get("fp1")).thenReturn(Optional.empty());
        when(store.saveIfAbsent(any())).thenReturn(true);

        ProcessResult result = processor.checkAndLock("fp1", store, 3600L);

        assertThat(result).isInstanceOf(ProcessResult.NotFound.class);
        verify(store).saveIfAbsent(argThat(r -> r.status() == IdempotencyStatus.IN_PROGRESS));
    }

    @Test
    @DisplayName("returns InProgress when existing record is IN_PROGRESS")
    void returnsInProgress_whenRecordIsInProgress() {
        IdempotencyRecord inProgress = IdempotencyRecord.inProgress("fp2", 3600L);
        when(store.get("fp2")).thenReturn(Optional.of(inProgress));

        ProcessResult result = processor.checkAndLock("fp2", store, 3600L);

        assertThat(result).isInstanceOf(ProcessResult.InProgress.class);
        verify(store, never()).saveIfAbsent(any());
    }

    @Test
    @DisplayName("returns Completed when existing record is COMPLETED")
    void returnsCompleted_whenRecordIsCompleted() {
        IdempotencyRecord completed = IdempotencyRecord.completed(
                "fp3", 200, Map.of(), "{}", 3600L);
        when(store.get("fp3")).thenReturn(Optional.of(completed));

        ProcessResult result = processor.checkAndLock("fp3", store, 3600L);

        assertThat(result).isInstanceOf(ProcessResult.Completed.class);
        ProcessResult.Completed c = (ProcessResult.Completed) result;
        assertThat(c.record().httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("returns InProgress when race condition loses the saveIfAbsent")
    void returnsInProgress_whenRaceConditionLosesLock() {
        when(store.get("fp4")).thenReturn(Optional.empty());
        when(store.saveIfAbsent(any())).thenReturn(false); // another thread won

        ProcessResult result = processor.checkAndLock("fp4", store, 3600L);

        assertThat(result).isInstanceOf(ProcessResult.InProgress.class);
    }

    // ── completeSuccess ───────────────────────────────────────────────────────

    @Test
    @DisplayName("completeSuccess stores a COMPLETED record in the store")
    void completeSuccess_storesCompletedRecord() {
        ResponseCapture.CapturedResponse captured = new ResponseCapture.CapturedResponse(
                201,
                Map.of("Content-Type", List.of("application/json")),
                "{\"id\":\"99\"}"
        );

        processor.completeSuccess("fp5", captured, store, 3600L);

        verify(store).update(argThat(record ->
                record.fingerprint().equals("fp5")
                && record.status() == IdempotencyStatus.COMPLETED
                && record.httpStatus() == 201
                && record.body().equals("{\"id\":\"99\"}")
        ));
    }

    // ── releaseLock ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("releaseLock deletes the fingerprint from the store")
    void releaseLock_deletesRecord() {
        processor.releaseLock("fp6", store);
        verify(store).delete("fp6");
    }
}
