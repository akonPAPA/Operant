package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P1-E lifecycle (operational-event slice) proof that the explicit producer emits typed events only on
 * real transitions (no flooding on stable polls), maps only known components (unknown ignored, fail
 * closed), and never ingests an arbitrary message.
 */
class OperationalEventRecorderTest {
  private final OperationalEventBuffer buffer = new OperationalEventBuffer();
  private final OperationalEventRecorder recorder =
      new OperationalEventRecorder(buffer, Clock.fixed(Instant.parse("2026-07-19T10:00:00Z"), ZoneOffset.UTC));

  @Test
  void emitsDependencyEventOnlyOnTransition() {
    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));
    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.UP)));
    assertThat(buffer.snapshotNewestFirst()).hasSize(1);

    recorder.observeDependencies(List.of(new DependencyStatus("database", DependencyState.DOWN)));
    List<OperationalEvent> events = buffer.snapshotNewestFirst();
    assertThat(events).hasSize(2);
    OperationalEvent latest = events.get(0);
    assertThat(latest.code()).isEqualTo(OperationalEventCode.DEPENDENCY_STATE_CHANGED);
    assertThat(latest.component()).isEqualTo(OperationalEventComponent.DATABASE);
    assertThat(latest.severity()).isEqualTo(OperationalEventSeverity.ERROR);
    assertThat(latest.summary()).isEqualTo("dependency DATABASE state changed to DOWN");
    assertThat(latest.correlationId()).isNull();
  }

  @Test
  void ignoresUnknownComponentNames() {
    recorder.observeDependencies(List.of(new DependencyStatus("kafka", DependencyState.DOWN)));
    assertThat(buffer.snapshotNewestFirst()).isEmpty();
  }

  @Test
  void emitsReadinessEventOnlyOnTransition() {
    recorder.observeReadiness(true);
    recorder.observeReadiness(true);
    assertThat(buffer.snapshotNewestFirst()).hasSize(1);

    recorder.observeReadiness(false);
    List<OperationalEvent> events = buffer.snapshotNewestFirst();
    assertThat(events).hasSize(2);
    assertThat(events.get(0).code()).isEqualTo(OperationalEventCode.READINESS_STATE_CHANGED);
    assertThat(events.get(0).component()).isEqualTo(OperationalEventComponent.PLATFORM);
    assertThat(events.get(0).severity()).isEqualTo(OperationalEventSeverity.WARN);
    assertThat(events.get(0).summary()).isEqualTo("platform readiness changed to NOT_READY");
  }
}
