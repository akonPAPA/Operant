package com.orderpilot.domain.usage;

/**
 * OP-CAP-16B Usage Metering Foundation — the quota-relevant metric a {@code UsageEvent} contributes
 * to and that {@code UsageCounter} aggregates. Quota policies are defined per metric type.
 */
public enum UsageMetricType {
  /** AI/heavy-worker input units (the primary 16B consumption metric). */
  AI_INPUT_UNITS,
  /** Count of AI routing decisions produced. */
  AI_ROUTING_DECISION,
  /** Count of documents uploaded. */
  DOCUMENT_UPLOAD,
  /** Count of inbound channel messages. */
  CHANNEL_MESSAGE,
  /** Count of draft quotes created. */
  DRAFT_QUOTE_CREATED,
  /** Count of draft orders created. */
  DRAFT_ORDER_CREATED,
  /** Count of reconciliation runs. */
  RECONCILIATION_RUN,
  /** Count of integration/connector syncs. */
  INTEGRATION_SYNC,
  /** OP-CAP-16G: count of report/export generations (the quota dimension for REPORT_GENERATED). */
  REPORT_GENERATED
}
