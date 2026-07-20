package com.orderpilot.application.services.control;

import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventPage;
import com.orderpilot.api.dto.ControlInternalDtos.OperationalEventProjection;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * P1-E lifecycle (operational-event slice) - bounded, cursor-paginated read over the process-local
 * {@link OperationalEventBuffer}, projected to the safe typed {@link OperationalEventProjection}. All
 * client-influenced values are bounded here: severity/component/eventCode are exact enum allowlists,
 * {@code limit} is clamped, and {@code before} is a numeric sequence boundary. Any malformed input
 * fails closed with {@link InvalidOperationalEventQueryException} (mapped to 400). Newest-first;
 * {@code before} is the exclusive upper sequence bound.
 */
@Service
public class OperationalEventReadService {
  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 100;
  /** Honest fixed contract token: process-local, non-durable recent operational events. */
  public static final String SCOPE = "LOCAL_PROCESS_RECENT_OPERATIONAL_EVENTS";
  static final String INSTANCE_ID = java.util.UUID.randomUUID().toString();

  private static final int MAX_CURSOR_DIGITS = 19;

  private final OperationalEventBuffer buffer;

  public OperationalEventReadService(OperationalEventBuffer buffer) {
    this.buffer = buffer;
  }

  public OperationalEventPage read(String severity, String component, String eventCode, String limit, String before) {
    OperationalEventSeverity severityFilter = parseEnum(severity, OperationalEventSeverity.class);
    OperationalEventComponent componentFilter = parseEnum(component, OperationalEventComponent.class);
    OperationalEventCode codeFilter = parseEnum(eventCode, OperationalEventCode.class);
    int pageLimit = parseLimit(limit);
    long beforeSequenceExclusive = parseBefore(before);

    List<OperationalEvent> snapshot = buffer.snapshotNewestFirst();
    List<OperationalEventProjection> page = new ArrayList<>(pageLimit);
    boolean hasMore = false;
    long lastIncludedSequence = -1L;
    for (OperationalEvent event : snapshot) {
      if (event.sequence() >= beforeSequenceExclusive) {
        continue;
      }
      if (severityFilter != null && event.severity() != severityFilter) {
        continue;
      }
      if (componentFilter != null && event.component() != componentFilter) {
        continue;
      }
      if (codeFilter != null && event.code() != codeFilter) {
        continue;
      }
      if (page.size() == pageLimit) {
        hasMore = true;
        break;
      }
      page.add(project(event));
      lastIncludedSequence = event.sequence();
    }
    String nextCursor = hasMore && lastIncludedSequence >= 0 ? Long.toString(lastIncludedSequence) : null;
    return new OperationalEventPage(List.copyOf(page), nextCursor, hasMore, page.size(), MAX_LIMIT, SCOPE, INSTANCE_ID);
  }

  private static OperationalEventProjection project(OperationalEvent event) {
    return new OperationalEventProjection(
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.occurredAtEpochMillis())),
        event.code().name(),
        event.component().name(),
        event.severity().name(),
        event.summary(),
        event.correlationId());
  }

  private static <E extends Enum<E>> E parseEnum(String raw, Class<E> type) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    try {
      return Enum.valueOf(type, normalized);
    } catch (IllegalArgumentException unknown) {
      throw new InvalidOperationalEventQueryException();
    }
  }

  private static int parseLimit(String limit) {
    if (limit == null || limit.isBlank()) {
      return DEFAULT_LIMIT;
    }
    String trimmed = limit.trim();
    if (trimmed.length() > 9 || !isAllDigits(trimmed)) {
      throw new InvalidOperationalEventQueryException();
    }
    int parsed = Integer.parseInt(trimmed);
    if (parsed < 1) {
      throw new InvalidOperationalEventQueryException();
    }
    return Math.min(parsed, MAX_LIMIT);
  }

  private static long parseBefore(String before) {
    if (before == null || before.isBlank()) {
      return Long.MAX_VALUE;
    }
    String trimmed = before.trim();
    if (trimmed.length() > MAX_CURSOR_DIGITS || !isAllDigits(trimmed)) {
      throw new InvalidOperationalEventQueryException();
    }
    try {
      long parsed = Long.parseLong(trimmed);
      if (parsed < 0) {
        throw new InvalidOperationalEventQueryException();
      }
      return parsed;
    } catch (NumberFormatException overflow) {
      throw new InvalidOperationalEventQueryException();
    }
  }

  private static boolean isAllDigits(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  /** Malformed operational-event query input. Mapped to HTTP 400 by the controller, fail-closed. */
  public static final class InvalidOperationalEventQueryException extends RuntimeException {
    public InvalidOperationalEventQueryException() {
      super("invalid operational event query");
    }
  }
}
