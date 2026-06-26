package com.orderpilot.domain.support;

/**
 * OP-CAP-51 — closed allowlist of maintenance/update action categories that may be <b>recorded</b>. These
 * are audit/record categories only: recording one never triggers a deployment, migration, or external call.
 */
public enum MaintenanceActionType {
  /** A configuration/settings review or note. */
  CONFIG_REVIEW,
  /** A runtime/health diagnostic activity. */
  RUNTIME_DIAGNOSTIC,
  /** An update/release audit note (records that an update was performed elsewhere — does not perform it). */
  UPDATE_AUDIT,
  /** Planning note for a future controlled data-repair (no execution). */
  DATA_REPAIR_PLANNING,
  /** Any other bounded, reasoned maintenance note. */
  OTHER
}
