package com.orderpilot.application.services.control;

/**
 * P1-E lifecycle (operational-event slice) - the closed, server-owned vocabulary for the bounded
 * operational-event projection exposed under {@code /api/v1/internal/control/operational-events}.
 *
 * <p>These are allowlists, not free-form data. {@link OperationalEventCode} and
 * {@link OperationalEventComponent} are closed enums; a producer can only emit a value that exists
 * here, and the read surface never serializes anything outside these tokens plus a bounded,
 * server-generated {@code summary}. Ordinary application logging never becomes an operational event -
 * events are emitted explicitly from approved operational boundaries via {@link OperationalEventRecorder}.
 *
 * <p>Lifecycle-operation codes (backup/restore/upgrade/rollback) are intentionally ABSENT: those
 * operations are not implemented in this slice, and this program must never fabricate events for
 * operations that do not exist. The slice(s) that implement them will add their own codes together
 * with a real producer.
 */
final class OperationalEventTypes {
  private OperationalEventTypes() {}
}

/** Closed set of operational event codes with a real producer in this slice. */
enum OperationalEventCode {
  DEPENDENCY_STATE_CHANGED,
  READINESS_STATE_CHANGED
}

/** Closed set of operational components an event may attribute to (never a host/URL/path). */
enum OperationalEventComponent {
  DATABASE,
  REDIS,
  PLATFORM
}

/** Closed severity vocabulary. */
enum OperationalEventSeverity {
  INFO,
  WARN,
  ERROR
}

/**
 * One retained, fully-typed operational event. Every field is server-owned: {@code sequence} is a
 * monotonic cursor unit; {@code summary} is built from a bounded server template (never an arbitrary
 * logger message); {@code correlationId} is optional and bounded. There is no tenant/actor/customer
 * identifier, payload, path, exception, or free-form metadata anywhere in this record.
 */
record OperationalEvent(
    long sequence,
    long occurredAtEpochMillis,
    OperationalEventCode code,
    OperationalEventComponent component,
    OperationalEventSeverity severity,
    String summary,
    String correlationId) {}
