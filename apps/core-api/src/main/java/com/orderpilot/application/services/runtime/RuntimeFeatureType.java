package com.orderpilot.application.services.runtime;

/**
 * OP-CAP-16D Runtime Guard Live Wiring — the set of high-cost runtime features whose availability can
 * be gated per tenant before any expensive work runs.
 *
 * <p>This is a stable token only; it carries no tenant plan internals or payload. Stage 16D enforces
 * exactly one feature ({@link #AI_DOCUMENT_EXTRACTION}) at a live boundary; the others are declared
 * so later stages can gate them without renaming.
 */
public enum RuntimeFeatureType {
  AI_DOCUMENT_EXTRACTION,
  AI_VALIDATION_HANDOFF,
  BULK_DOCUMENT_PROCESSING,
  RECONCILIATION_RUN,
  // OP-CAP-16F: gated at the bulk import activation boundary.
  BULK_IMPORT,
  // OP-CAP-16G: gated at the report/export generation boundary (operator-initiated; entitlement +
  // quota only).
  REPORT_EXPORT,
  // OP-CAP-16G: gated at the advisory AI explanation/summary generation boundary (full guard).
  AI_VALIDATION_EXPLANATION
}
