package com.orderpilot.application.services.control;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.orderpilot.security.ControlPlanePrincipal;
import com.orderpilot.security.ControlPlanePrincipalType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proof that privileged read audit records contain only a stable fingerprint, fixed permission,
 * booleans and a bounded count; raw principal/query/event data never reaches the log sink.
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
  void recordsPseudonymousAttributionAndBooleanRequestShape() {
    auditor.recordSuccess(
        new ControlPlanePrincipal(
            "staff:operations",
            "ops-prod",
            "control-v1",
            ControlPlanePrincipalType.OPERANT_STAFF),
        true, true, true, true, true, 25);

    String message = lastMessage();
    assertThat(message)
        .contains("result=SUCCESS")
        .contains("permission=STAFF_CONTROL_OPERATIONAL_EVENT_READ")
        .contains("severityFilterPresent=true")
        .contains("componentFilterPresent=true")
        .contains("eventCodeFilterPresent=true")
        .contains("customLimitPresent=true")
        .contains("beforePresent=true")
        .contains("returned=25")
        .matches(".*principalFingerprint=[0-9a-f]{64}.*")
        .doesNotContain("staff:operations")
        .doesNotContain("ops-prod")
        .doesNotContain("control-v1");
  }

  @Test
  void reportsUnknownWhenPrincipalAbsent() {
    auditor.recordSuccess(null, false, false, false, false, false, 0);

    assertThat(lastMessage())
        .contains("principalFingerprint=unknown")
        .contains("severityFilterPresent=false")
        .contains("componentFilterPresent=false")
        .contains("eventCodeFilterPresent=false")
        .contains("customLimitPresent=false")
        .contains("beforePresent=false")
        .contains("returned=0");
  }

  @Test
  void rawPrincipalTextAndLogForgingCharactersNeverReachTheSink() {
    String unicodeLineSeparator = new String(Character.toChars(0x2028));
    String unicodeParagraphSeparator = new String(Character.toChars(0x2029));
    String maliciousPrincipalId = "staff\nforged" + unicodeLineSeparator + "identity";
    String maliciousAlias = "ops\nforged" + unicodeLineSeparator + "entry";
    String maliciousVersion = "v1\r\nresult=FAIL" + unicodeParagraphSeparator;

    auditor.recordSuccess(
        new ControlPlanePrincipal(
            maliciousPrincipalId,
            maliciousAlias,
            maliciousVersion,
            ControlPlanePrincipalType.OPERANT_STAFF),
        true, false, true, false, true, -5);

    String message = lastMessage();
    assertThat(message)
        .matches(".*principalFingerprint=[0-9a-f]{64}.*")
        .contains("returned=0")
        .doesNotContain(maliciousPrincipalId)
        .doesNotContain(maliciousAlias)
        .doesNotContain(maliciousVersion)
        .doesNotContain("\n")
        .doesNotContain("\r")
        .doesNotContain(unicodeLineSeparator)
        .doesNotContain(unicodeParagraphSeparator);
  }
}
