package com.orderpilot.application.services.control;

import com.orderpilot.security.ControlPlanePrincipal;
import com.orderpilot.security.ControlPlanePrincipalFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * P1-E lifecycle (operational-event slice) - emits one bounded structured record for each successful
 * privileged operational-event read. The record carries a stable non-reversible principal fingerprint
 * and boolean request-shape facts only. It never logs raw principal fields, filter values, headers,
 * returned events, secrets, key material, or response content.
 *
 * <p>The dedicated logger namespace is distinct from the operational-event projection, so auditing a
 * read cannot feed the projection. A durable/persisted control-access audit store remains NOT_PROVEN.
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
      boolean severityFilterPresent,
      boolean componentFilterPresent,
      boolean eventCodeFilterPresent,
      boolean customLimitPresent,
      boolean beforePresent,
      int returnedCount) {
    auditLogger.info(
        "control-operational-event-access result=SUCCESS principalFingerprint={} permission={} "
            + "severityFilterPresent={} componentFilterPresent={} eventCodeFilterPresent={} "
            + "customLimitPresent={} beforePresent={} returned={}",
        ControlPlanePrincipalFingerprint.of(principal),
        PERMISSION,
        severityFilterPresent,
        componentFilterPresent,
        eventCodeFilterPresent,
        customLimitPresent,
        beforePresent,
        Math.max(0, returnedCount));
  }
}
