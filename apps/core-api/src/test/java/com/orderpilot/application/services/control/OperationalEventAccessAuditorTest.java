package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orderpilot.security.ControlPlanePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * P1-E lifecycle (operational-event slice) proof that a successful privileged read emits a bounded,
 * structured access record with attribution + request shape only - never content, secrets, or raw
 * headers - and that all inputs are defensively sanitized.
 */
class OperationalEventAccessAuditorTest {
  private Logger auditLogger;
  private ListAppender<ILoggingEvent> captured;
  private OperationalEventAccessAuditor auditor;

  @BeforeEach
  void attach() {
    auditLogger = (Logger) LoggerFactory.getLogger(OperationalEventAccessAuditor.AUDIT_LOGGER_NAME);
    captured = new ListAppender<>();
    captured.start();
    auditLogger.addAppender(captured);
    auditor = new OperationalEventAccessAuditor();
  }

  @AfterEach
  void detach() {
    auditLogger.detachAppender(captured);
  }

  private String lastMessage() {
    assertThat(captured.list).hasSize(1);
    return captured.list.get(0).getFormattedMessage();
  }

  @Test
  void recordsBoundedAttributionAndRequestShape() {
    auditor.recordSuccess(
        new ControlPlanePrincipal("ops-prod", "control-v1", "MAINTENANCE_SERVICE"),
        "warn", "database", "dependency_state_changed", "25", true, 25);

    assertThat(lastMessage())
        .contains("result=SUCCESS")
        .contains("principal=ops-prod")
        .contains("principalType=MAINTENANCE_SERVICE")
        .contains("keyVersion=control-v1")
        .contains("permission=STAFF_CONTROL_OPERATIONAL_EVENT_READ")
        .contains("severity=WARN")
        .contains("component=DATABASE")
        .contains("eventCode=DEPENDENCY_STATE_CHANGED")
        .contains("limit=25")
        .contains("beforePresent=true")
        .contains("returned=25");
  }

  @Test
  void reportsUnknownWhenPrincipalAbsentAndAllFiltersOmitted() {
    auditor.recordSuccess(null, null, null, null, null, false, 0);
    assertThat(lastMessage())
        .contains("principal=unknown")
        .contains("severity=ALL")
        .contains("component=ALL")
        .contains("eventCode=ALL")
        .contains("limit=default")
        .contains("beforePresent=false");
  }

  @Test
  void sanitizesMalformedInputsAndNegativeCount() {
    auditor.recordSuccess(
        new ControlPlanePrincipal("ops-prod", "control-v1", "MAINTENANCE_SERVICE"),
        "DROP TABLE", "../etc", "x'; --", "99999999999", true, -5);
    assertThat(lastMessage())
        .contains("severity=INVALID")
        .contains("component=INVALID")
        .contains("eventCode=INVALID")
        .contains("limit=invalid")
        .contains("returned=0");
  }
}
