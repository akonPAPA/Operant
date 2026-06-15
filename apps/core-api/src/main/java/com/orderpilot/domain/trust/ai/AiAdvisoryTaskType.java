package com.orderpilot.domain.trust.ai;

import java.util.List;

/**
 * OP-CAP-19 Layer B — Advisory AI Memory Retrieval.
 *
 * The kind of future runtime task a caller wants advisory hints for. Each task type maps to the bounded
 * set of tenant-scoped {@link AiMemoryNamespace}s whose governed memory may be relevant. This is NOT a
 * semantic/vector lookup — it is a deterministic namespace/task allowance used only to rank already-safe,
 * advisory, low-authority memory. Retrieved hints never become authoritative.
 */
public enum AiAdvisoryTaskType {
  DOCUMENT_EXTRACTION_ASSIST(AiMemoryNamespace.DOCUMENT_TEMPLATE, AiMemoryNamespace.EXTRACTION_CORRECTION),
  PRODUCT_MATCH_ASSIST(AiMemoryNamespace.PRODUCT_ALIAS_HINT),
  CUSTOMER_MATCH_ASSIST(AiMemoryNamespace.COUNTERPARTY_PATTERN),
  TRUST_SIGNAL_EXPLANATION(AiMemoryNamespace.TRUST_SIGNAL_HINT, AiMemoryNamespace.VALIDATION_EXPLANATION),
  PAYMENT_MATCH_ASSIST(AiMemoryNamespace.PAYMENT_MATCH_HINT),
  BOT_RESPONSE_ASSIST(AiMemoryNamespace.BOT_CONVERSATION_SUMMARY),
  IMPORT_MAPPING_ASSIST(AiMemoryNamespace.DOCUMENT_TEMPLATE, AiMemoryNamespace.PRODUCT_ALIAS_HINT),
  OPERATOR_REVIEW_ASSIST(AiMemoryNamespace.OPERATOR_CORRECTION_PATTERN, AiMemoryNamespace.EXTRACTION_CORRECTION);

  private final List<AiMemoryNamespace> namespaces;

  AiAdvisoryTaskType(AiMemoryNamespace... namespaces) {
    this.namespaces = List.of(namespaces);
  }

  /** Bounded set of namespaces this task type considers relevant (for the TASK_NAMESPACE_MATCH signal). */
  public List<AiMemoryNamespace> relevantNamespaces() {
    return namespaces;
  }

  public boolean matches(AiMemoryNamespace namespace) {
    return namespaces.contains(namespace);
  }
}
