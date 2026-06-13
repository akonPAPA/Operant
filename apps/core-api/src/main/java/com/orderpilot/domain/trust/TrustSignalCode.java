package com.orderpilot.domain.trust;

/**
 * OP-CAP-17A Document Trust Signal Foundation.
 *
 * Closed taxonomy of deterministic document trust signals. Each code maps to a single explainable
 * rule. Codes are risk indicators only; none of them asserts that a document is fake or fraudulent.
 */
public enum TrustSignalCode {
  /** Document/issue date is later than the evaluation time. */
  DOCUMENT_DATE_IN_FUTURE,
  /** Payment due date is earlier than the document issue date. */
  DUE_DATE_BEFORE_ISSUE_DATE,
  /** Identical document content hash already seen for the same tenant. */
  DUPLICATE_DOCUMENT_HASH,
  /** Bank account holder differs from the expected counterparty. */
  BANK_ACCOUNT_HOLDER_MISMATCH,
  /** OCR confidence on a critical field is below the acceptance threshold. */
  OCR_CONFIDENCE_LOW_CRITICAL_FIELD,
  /** Declared document total does not reconcile with the computed line-item total. */
  DOCUMENT_TOTAL_MATH_MISMATCH
}
