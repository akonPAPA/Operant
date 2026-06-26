package com.orderpilot.domain.support;

/**
 * OP-CAP-51 — closed allowlist of data-repair target categories. There is deliberately no free-form SQL,
 * script, or raw-table target: a repair can only ever name one of these bounded business areas, and even
 * then only as a dry-run in this stage.
 */
public enum DataRepairTargetType {
  ORDER_JOURNEY,
  QUOTE,
  DRAFT,
  PROCESSING_JOB,
  OTHER
}
