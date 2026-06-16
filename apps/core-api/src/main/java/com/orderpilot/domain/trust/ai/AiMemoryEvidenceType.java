package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * The kind of upstream evidence a normalized evidence reference points at. Evidence is a bounded pointer
 * (type + reference + optional source id/field key) — never raw text, OCR, or extracted values.
 */
public enum AiMemoryEvidenceType {
  FIELD,
  DOCUMENT,
  DECISION,
  SIGNAL,
  PAYMENT,
  COUNTERPARTY,
  CORRECTION
}
