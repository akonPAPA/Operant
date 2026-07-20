package com.orderpilot.application.services.control;

/**
 * P1-E lifecycle (operational-event slice) - server-owned, bounded summary construction. Summaries
 * are built ONLY from closed enum tokens via fixed templates here; a producer never passes an
 * arbitrary application/log message through. All output is length-bounded and control-character free.
 */
final class OperationalEventSummaries {
  static final int MAX_SUMMARY_LENGTH = 160;
  static final int MAX_CORRELATION_LENGTH = 64;

  private OperationalEventSummaries() {}

  /** Fixed template for a dependency state change. Uses only closed tokens. */
  static String dependencyStateChanged(OperationalEventComponent component, String state) {
    return "dependency " + component.name() + " state changed to " + safeState(state);
  }

  /** Fixed template for a readiness state change. */
  static String readinessStateChanged(boolean ready) {
    return "platform readiness changed to " + (ready ? "READY" : "NOT_READY");
  }

  /** Defensive bound applied to any stored summary (templates are already safe). */
  static String bound(String summary) {
    if (summary == null) {
      return "";
    }
    String stripped = stripControl(summary);
    return stripped.length() > MAX_SUMMARY_LENGTH ? stripped.substring(0, MAX_SUMMARY_LENGTH) : stripped;
  }

  /** Bound an optional correlation id to a safe, short token; blank/oversized becomes {@code null}. */
  static String boundCorrelationId(String correlationId) {
    if (correlationId == null || correlationId.isBlank() || correlationId.length() > MAX_CORRELATION_LENGTH) {
      return null;
    }
    for (int i = 0; i < correlationId.length(); i++) {
      char character = correlationId.charAt(i);
      boolean allowed = (character >= 'A' && character <= 'Z')
          || (character >= 'a' && character <= 'z')
          || (character >= '0' && character <= '9')
          || character == '-' || character == '_';
      if (!allowed) {
        return null;
      }
    }
    return correlationId;
  }

  private static String safeState(String state) {
    // Dependency state tokens are a closed vocabulary (UP/DOWN/NOT_CONFIGURED); guard anyway.
    return switch (state == null ? "" : state) {
      case "UP", "DOWN", "NOT_CONFIGURED" -> state;
      default -> "UNKNOWN";
    };
  }

  private static String stripControl(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      builder.append(Character.isISOControl(character) ? ' ' : character);
    }
    return builder.toString().trim();
  }
}
