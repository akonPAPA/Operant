package com.orderpilot.domain.risk;

public enum ValidationRiskDecision {
  AUTO_READY_DRAFT,
  NEEDS_OPERATOR_REVIEW,
  REQUIRES_MANAGER_APPROVAL,
  BLOCKED
}
