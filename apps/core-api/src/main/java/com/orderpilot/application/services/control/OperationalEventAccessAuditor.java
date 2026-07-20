package com.orderpilot.application.services.control;

import com.orderpilot.security.ControlPlanePrincipal;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1-E lifecycle (operational-event slice) - emits a bounded, structured access record for each
 * successful privileged operational-event read. It carries attribution + request-shape metadata only:
 * never returned events, secrets, headers, key material, or sensitive query values. It writes to a
 * dedicated logger namespace, distinct from application logging and from the operational-event
 * projection (which is producer-fed, not logger-fed), so auditing a read cannot feed the projection.
 * This is a structured operational audit channel; a durable/persisted control-access audit store
 * remains a later slice (NOT_PROVEN).
 */
public final class OperationalEventAccessAuditor {
  public static final String AUDIT_LOGGER_NAME = "com.orderpilot.security.control.audit.OperationalEventAccess";
  static final String PERMISSION = "STAFF_CONTROL_OPERATIONAL_EVENT_READ";

  private final Logger auditLogger;

  public OperationalEventAccessAuditor() {
    this(LoggerFactory.getLogger(AUDIT_LOGGER_NAME));
  }

  OperationalEventAccessAuditor(Logger auditLogger) {
    this.auditLogger = auditLogger;
  }

  public void recordSuccess(
      ControlPlanePrincipal principal,
      String requestedSeverity,
      String requestedComponent,
      String requestedEventCode,
      String requestedLimit,
      boolean beforePresent,
      int returnedCount) {
    String alias = principal == null ? "unknown" : safeToken(principal.credentialAlias());
    String type = principal == null ? "unknown" : safeToken(principal.principalType());
    String keyVersion = principal == null ? "unknown" : safeToken(principal.keyVersion());
    auditLogger.info(
        "control-operational-event-access result=SUCCESS principal={} principalType={} keyVersion={} "
            + "permission={} severity={} component={} eventCode={} limit={} beforePresent={} returned={}",
        alias,
        type,
        keyVersion,
        PERMISSION,
        allowlistToken(requestedSeverity),
        allowlistToken(requestedComponent),
        allowlistToken(requestedEventCode),
        safeLimit(requestedLimit),
        beforePresent,
        Math.max(0, returnedCount));
  }

  private static String allowlistToken(String raw) {
    if (raw == null || raw.isBlank()) {
      return "ALL";
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (!((c >= 'A' && c <= 'Z') || c == '_')) {
        return "INVALID";
      }
    }
    return normalized.length() > 48 ? "INVALID" : normalized;
  }

  private static String safeLimit(String requestedLimit) {
    if (requestedLimit == null || requestedLimit.isBlank()) {
      return "default";
    }
    String trimmed = requestedLimit.trim();
    if (trimmed.length() > 9) {
      return "invalid";
    }
    for (int i = 0; i < trimmed.length(); i++) {
      if (trimmed.charAt(i) < '0' || trimmed.charAt(i) > '9') {
        return "invalid";
      }
    }
    return trimmed;
  }

  private static String safeToken(String raw) {
    if (raw == null || raw.isBlank()) {
      return "unknown";
    }
    StringBuilder builder = new StringBuilder(Math.min(raw.length(), 64));
    for (int i = 0; i < raw.length() && builder.length() < 64; i++) {
      char character = raw.charAt(i);
      builder.append(Character.isISOControl(character) || character == ' ' ? '_' : character);
    }
    return builder.toString();
  }
}
