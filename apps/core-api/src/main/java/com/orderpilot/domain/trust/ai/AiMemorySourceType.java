package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * The kind of approved OrderPilot workflow record a memory item was derived from. The matching
 * {@code sourceId} points at an existing tenant-scoped record (e.g. a document trust run, a trust risk
 * decision) — it never duplicates that record's raw payload.
 */
public enum AiMemorySourceType {
  DOCUMENT_TRUST_RUN,
  TRUST_RISK_DECISION,
  COUNTERPARTY_PROFILE,
  PAYMENT_OBLIGATION,
  VALIDATION_RUN,
  OPERATOR_CORRECTION,
  BOT_CONVERSATION,
  IMPORT_JOB,
  SYSTEM
}
