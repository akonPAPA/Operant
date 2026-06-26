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
  // OP-CAP-54 — the FIRST and ONLY bounded target for which real, approval-gated execution exists. It
  // repairs operational processing-job status only (a wedged PENDING/PROCESSING job → terminal FAILED so
  // the normal control-plane retry path can requeue it). It is NOT a generic table/row/SQL/script target:
  // there is no free-form target name, no arbitrary field patch, and no business order/quote/inventory/
  // customer/price mutation. Every other target remains dry-run / execution-disabled.
  PROCESSING_JOB_STATUS_REPAIR,
  OTHER
}
