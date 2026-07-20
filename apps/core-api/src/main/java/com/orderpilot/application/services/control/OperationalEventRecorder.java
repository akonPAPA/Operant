package com.orderpilot.application.services.control;

import com.orderpilot.api.dto.ControlInternalDtos.DependencyState;
import com.orderpilot.api.dto.ControlInternalDtos.DependencyStatus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * P1-E lifecycle (operational-event slice) - the explicit producer of operational events. It is the
 * ONLY thing that writes to {@link OperationalEventBuffer}, and it emits only from approved
 * operational boundaries (observed dependency/readiness state). It emits an event exactly on a
 * transition, so routine polling does not flood the buffer, and it maps only known components -
 * an unrecognized dependency name is ignored (fail closed), never projected.
 *
 * <p>No arbitrary logger record can reach this producer: callers pass typed platform state, and the
 * summary is built from a fixed server template.
 */
@Component
public class OperationalEventRecorder {
  private final OperationalEventBuffer buffer;
  private final java.time.Clock clock;
  private final Map<OperationalEventComponent, DependencyState> lastDependencyState = new ConcurrentHashMap<>();
  private final Object readinessLock = new Object();
  private Boolean lastReadiness;

  @org.springframework.beans.factory.annotation.Autowired
  public OperationalEventRecorder(OperationalEventBuffer buffer) {
    this(buffer, java.time.Clock.systemUTC());
  }

  OperationalEventRecorder(OperationalEventBuffer buffer, java.time.Clock clock) {
    this.buffer = buffer;
    this.clock = clock;
  }

  /** Observe a set of dependency statuses; emit DEPENDENCY_STATE_CHANGED for each real transition. */
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
      DependencyState previous = lastDependencyState.put(component, state);
      if (previous != state) {
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

  /** Observe platform readiness; emit READINESS_STATE_CHANGED only on a transition. */
  public void observeReadiness(boolean ready) {
    synchronized (readinessLock) {
      if (lastReadiness != null && lastReadiness == ready) {
        return;
      }
      lastReadiness = ready;
    }
    buffer.append(
        clock.millis(),
        OperationalEventCode.READINESS_STATE_CHANGED,
        OperationalEventComponent.PLATFORM,
        ready ? OperationalEventSeverity.INFO : OperationalEventSeverity.WARN,
        OperationalEventSummaries.readinessStateChanged(ready),
        null);
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
