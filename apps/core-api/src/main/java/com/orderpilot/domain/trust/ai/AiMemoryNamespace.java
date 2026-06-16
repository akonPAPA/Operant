package com.orderpilot.domain.trust.ai;

/**
 * OP-CAP-17F AI Data Runtime / Tenant-Scoped AI Memory Governance.
 *
 * Tenant-scoped logical namespace for reusable, bounded, sanitized AI/runtime knowledge derived from
 * approved OrderPilot workflow events. A namespace is always paired with a tenant — there is never a
 * global/cross-tenant namespace. Memory in any namespace is advisory and low-authority; it is never the
 * source of truth for orders, quotes, prices, stock, payments, counterparty trust, or approval status.
 */
public enum AiMemoryNamespace {
  DOCUMENT_TEMPLATE,
  PRODUCT_ALIAS_HINT,
  COUNTERPARTY_PATTERN,
  EXTRACTION_CORRECTION,
  VALIDATION_EXPLANATION,
  TRUST_SIGNAL_HINT,
  PAYMENT_MATCH_HINT,
  BOT_CONVERSATION_SUMMARY,
  OPERATOR_CORRECTION_PATTERN
}
