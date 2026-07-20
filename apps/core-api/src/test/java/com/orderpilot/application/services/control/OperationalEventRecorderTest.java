package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proof that sampled state baselines do not fabricate transitions and that state update plus event
 * append is linearizable under concurrent control endpoint polls.
 */
class OperationalEventRecorderTest {
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneOffset.UTC);

  @Test
  void firstDependencyObservationEstablishesBaselineWithoutFabricatingTransition() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);

    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));
    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));

    assertThat(buffer.snapshotNewestFirst()).isEmpty();
  }

  @Test
  void emitsDependencyEventOnlyOnSubsequentChangedObservation() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);
    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));

    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.DOWN)));

    List<OperationalEvent> events = buffer.snapshotNewestFirst();
    assertThat(events).hasSize(1);
    OperationalEvent latest = events.get(0);
    assertThat(latest.code()).isEqualTo(OperationalEventCode.DEPENDENCY_STATE_CHANGED);
    assertThat(latest.component()).isEqualTo(OperationalEventComponent.DATABASE);
    assertThat(latest.severity()).isEqualTo(OperationalEventSeverity.ERROR);
    assertThat(latest.summary()).isEqualTo("observed dependency DATABASE state changed to DOWN");
    assertThat(latest.correlationId()).isNull();
  }

  @Test
  void ignoresUnknownComponentNames() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);

    recorder.observeDependencies(List.of(new DependencyStatus("kafka", DependencyState.DOWN)));

    assertThat(buffer.snapshotNewestFirst()).isEmpty();
  }

  @Test
  void firstReadinessObservationEstablishesBaselineWithoutFabricatingTransition() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);

    recorder.observeReadiness(true);
    recorder.observeReadiness(true);

    assertThat(buffer.snapshotNewestFirst()).isEmpty();
  }

  @Test
  void emitsReadinessEventOnlyOnSubsequentChangedObservation() {
    OperationalEventBuffer buffer = new OperationalEventBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);
    recorder.observeReadiness(true);

    recorder.observeReadiness(false);

    List<OperationalEvent> events = buffer.snapshotNewestFirst();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).code()).isEqualTo(OperationalEventCode.READINESS_STATE_CHANGED);
    assertThat(events.get(0).component()).isEqualTo(OperationalEventComponent.PLATFORM);
    assertThat(events.get(0).severity()).isEqualTo(OperationalEventSeverity.WARN);
    assertThat(events.get(0).summary()).isEqualTo("observed platform readiness changed to NOT_READY");
  }

  @Test
  void dependencyTransitionsRemainOrderedWhenFirstAppendIsBlocked() throws Exception {
    BlockingFirstAppendBuffer buffer = new BlockingFirstAppendBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);
    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> down = executor.submit(() -> recorder.observeDependencies(
          List.of(new DependencyStatus("database", DependencyState.DOWN))));
      assertThat(buffer.firstAppendEntered.await(2, TimeUnit.SECONDS)).isTrue();

      CountDownLatch secondTaskStarted = new CountDownLatch(1);
      Future<?> up = executor.submit(() -> {
        secondTaskStarted.countDown();
        recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));
      });
      assertThat(secondTaskStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(buffer.secondAppendEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

      buffer.releaseFirstAppend.countDown();
      down.get(2, TimeUnit.SECONDS);
      up.get(2, TimeUnit.SECONDS);
    }

    assertThat(buffer.snapshotNewestFirst())
        .extracting(OperationalEvent::summary)
        .containsExactly(
            "observed dependency DATABASE state changed to UP",
            "observed dependency DATABASE state changed to DOWN");
  }

  @Test
  void readinessTransitionsRemainOrderedWhenFirstAppendIsBlocked() throws Exception {
    BlockingFirstAppendBuffer buffer = new BlockingFirstAppendBuffer();
    OperationalEventRecorder recorder = new OperationalEventRecorder(buffer, FIXED_CLOCK);
    recorder.observeReadiness(true);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<?> notReady = executor.submit(() -> recorder.observeReadiness(false));
      assertThat(buffer.firstAppendEntered.await(2, TimeUnit.SECONDS)).isTrue();

      CountDownLatch secondTaskStarted = new CountDownLatch(1);
      Future<?> ready = executor.submit(() -> {
        secondTaskStarted.countDown();
        recorder.observeReadiness(true);
      });
      assertThat(secondTaskStarted.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(buffer.secondAppendEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

      buffer.releaseFirstAppend.countDown();
      notReady.get(2, TimeUnit.SECONDS);
      ready.get(2, TimeUnit.SECONDS);
    }

    assertThat(buffer.snapshotNewestFirst())
        .extracting(OperationalEvent::summary)
        .containsExactly(
            "observed platform readiness changed to READY",
            "observed platform readiness changed to NOT_READY");
  }

  private static final class BlockingFirstAppendBuffer extends OperationalEventBuffer {
    private final AtomicInteger appendCalls = new AtomicInteger();
    private final CountDownLatch firstAppendEntered = new CountDownLatch(1);
    private final CountDownLatch secondAppendEntered = new CountDownLatch(1);
    private final CountDownLatch releaseFirstAppend = new CountDownLatch(1);

    @Override
    long append(
        long occurredAtEpochMillis,
        OperationalEventCode code,
        OperationalEventComponent component,
        OperationalEventSeverity severity,
        String summary,
        String correlationId) {
      int call = appendCalls.incrementAndGet();
      if (call == 1) {
        firstAppendEntered.countDown();
        await(releaseFirstAppend);
      } else if (call == 2) {
        secondAppendEntered.countDown();
      }
      return super.append(
          occurredAtEpochMillis, code, component, severity, summary, correlationId);
    }

    private static void await(CountDownLatch latch) {
      try {
        if (!latch.await(2, TimeUnit.SECONDS)) {
          throw new IllegalStateException("timed out waiting for test latch");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("test interrupted", interrupted);
      }
    }
  }
}
