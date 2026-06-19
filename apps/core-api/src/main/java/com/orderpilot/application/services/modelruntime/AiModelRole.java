package com.orderpilot.application.services.modelruntime;

/**
 * Advisory model roles known to the bounded model-runtime foundation.
 *
 * <p>A role describes the review task only. It never grants approval, execution, connector, tenant,
 * actor, status, pricing, stock, or other business authority.
 */
public enum AiModelRole {
  CODE_REVIEW,
  BUSINESS_LOGIC_REVIEW,
  PRODUCT_SECURITY_GATE,
  EXTRACTION_ADVISORY
}
