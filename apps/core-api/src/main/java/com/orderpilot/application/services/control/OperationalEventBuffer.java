package com.orderpilot.application.services.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * P1-E lifecycle (operational-event slice) - the bounded, process-local source of truth for the
 * operational-event projection. A fixed-capacity ring: append is O(1) and evicts the oldest entry
 * once {@link #CAPACITY} is reached, so retained window and memory are inherently bounded. It stores
 * only fully-typed {@link OperationalEvent} values produced by {@link OperationalEventRecorder} - it
 * has no knowledge of logging and cannot ingest an arbitrary message. NON-DURABLE: a restart clears
 * it, and it aggregates nothing across instances.
 */
@Component
public class OperationalEventBuffer {
  /** Fixed maximum number of retained operational events. */
  public static final int CAPACITY = 500;

  private final int capacity;
  private final Object lock = new Object();
  private final ArrayDeque<OperationalEvent> ring;
  private long nextSequence = 1L;

  public OperationalEventBuffer() {
    this(CAPACITY);
  }

  OperationalEventBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    this.ring = new ArrayDeque<>(capacity);
  }

  /**
   * Append a typed event with server-assigned monotonic sequence and timestamp. Returns the sequence.
   * {@code summary} is truncated defensively; the buffer never accepts a null code/component/severity.
   */
  long append(
      long occurredAtEpochMillis,
      OperationalEventCode code,
      OperationalEventComponent component,
      OperationalEventSeverity severity,
      String summary,
      String correlationId) {
    if (code == null || component == null || severity == null) {
      throw new IllegalArgumentException("operational event requires code, component and severity");
    }
    String safeSummary = OperationalEventSummaries.bound(summary);
    String safeCorrelation = OperationalEventSummaries.boundCorrelationId(correlationId);
    synchronized (lock) {
      long sequence = nextSequence++;
      if (ring.size() >= capacity) {
        ring.removeFirst();
      }
      ring.addLast(new OperationalEvent(
          sequence, occurredAtEpochMillis, code, component, severity, safeSummary, safeCorrelation));
      return sequence;
    }
  }

  /** Immutable snapshot, newest first (descending sequence). O(size), size bounded by capacity. */
  List<OperationalEvent> snapshotNewestFirst() {
    synchronized (lock) {
      List<OperationalEvent> copy = new ArrayList<>(ring);
      Collections.reverse(copy);
      return List.copyOf(copy);
    }
  }

  int capacity() {
    return capacity;
  }
}
