package com.orderpilot.application.services.control;

import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * P1-E lifecycle (operational-event slice) - the explicit producer of sampled control-state
 * observations. It is the ONLY thing that writes to {@link OperationalEventBuffer}. Status and
 * readiness endpoint polling supplies typed dependency/readiness observations; the first observation
 * establishes an in-process baseline and emits no transition event. A subsequent changed observation
 * emits exactly one event.
 *
 * <p>The recorder is linearizable: baseline/current-state update and event append execute under one
 * lock, so concurrent polls cannot publish an event order that contradicts the recorder's final
 * observed state. A DOWN observation is the bounded control-probe result and may represent dependency
 * unreachability, probe timeout, bulkhead saturation, or probe failure; it is not an independent
 * background incident detector.
 *
 * <p>No arbitrary logger record can reach this producer: callers pass typed platform state, unknown
 * components are ignored fail-closed, and summaries are built from fixed server templates.
 */
@Component
public class OperationalEventRecorder {
  private final OperationalEventBuffer buffer;
  private final java.time.Clock clock;
  private final Object stateLock = new Object();
  private final Map<OperationalEventComponent, DependencyState> lastDependencyState = new HashMap<>();
  private Boolean lastReadiness;

  @org.springframework.beans.factory.annotation.Autowired
  public OperationalEventRecorder(OperationalEventBuffer buffer) {
    this(buffer, java.time.Clock.systemUTC());
  }

  OperationalEventRecorder(OperationalEventBuffer buffer, java.time.Clock clock) {
    this.buffer = buffer;
    this.clock = clock;
  }

  /**
   * Observe dependency probe states. The first value per component establishes a baseline; only a
   * later changed sampled value emits DEPENDENCY_STATE_CHANGED.
   */
  public void observeDependencies(List<DependencyStatus> dependencies) {
    if (dependencies == null) {
      return;
    }
    for (DependencyStatus dependency : dependencies) {
      if (dependency == null) {
        continue;
      }
      OperationalEventComponent component = componentFor(dependency.name());
      DependencyState state = dependency.state();
      if (component == null || state == null) {
        continue;
      }
      synchronized (stateLock) {
        if (!lastDependencyState.containsKey(component)) {
          lastDependencyState.put(component, state);
          continue;
        }
        DependencyState previous = lastDependencyState.get(component);
        if (previous == state) {
          continue;
        }
        lastDependencyState.put(component, state);
        buffer.append(
            clock.millis(),
            OperationalEventCode.DEPENDENCY_STATE_CHANGED,
            component,
            severityForDependency(state),
            OperationalEventSummaries.dependencyStateChanged(component, state.name()),
            null);
      }
    }
  }

  /**
   * Observe sampled platform readiness. The first value establishes a baseline; only a later changed
   * sampled value emits READINESS_STATE_CHANGED.
   */
  public void observeReadiness(boolean ready) {
    synchronized (stateLock) {
      if (lastReadiness == null) {
        lastReadiness = ready;
        return;
      }
      if (lastReadiness == ready) {
        return;
      }
      lastReadiness = ready;
      buffer.append(
          clock.millis(),
          OperationalEventCode.READINESS_STATE_CHANGED,
          OperationalEventComponent.PLATFORM,
          ready ? OperationalEventSeverity.INFO : OperationalEventSeverity.WARN,
          OperationalEventSummaries.readinessStateChanged(ready),
          null);
    }
  }

  private static OperationalEventComponent componentFor(String dependencyName) {
    if (dependencyName == null) {
      return null;
    }
    return switch (dependencyName) {
      case "database" -> OperationalEventComponent.DATABASE;
      case "redis" -> OperationalEventComponent.REDIS;
      default -> null;
    };
  }

  private static OperationalEventSeverity severityForDependency(DependencyState state) {
    return state == DependencyState.DOWN ? OperationalEventSeverity.ERROR : OperationalEventSeverity.INFO;
  }
}
