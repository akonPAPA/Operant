package com.orderpilot.application.services.runtime;

/**
 * Coarse classification of the kind of AI/OCR/heavy worker workload a request would trigger.
 *
 * <p>Stage 16A foundation only — used by the deterministic {@link AiWorkloadClassifier} to make
 * routing decisions. It does not select a provider, call AI, or change execution behavior.
 */
public enum AiWorkloadType {
  CHAT_INTENT,
  PRICE_REQUEST,
  AVAILABILITY_REQUEST,
  PURCHASE_LIST_EXTRACTION,
  DOCUMENT_EXTRACTION,
  PRODUCT_MATCHING,
  VALIDATION_EXPLANATION,
  DRAFT_GENERATION,
  ERP_RECONCILIATION,
  PAYMENT_RECONCILIATION,
  BULK_IMPORT,
  UNKNOWN
}
