package com.orderpilot.security;

public enum ApiPermission {
  ANALYTICS_READ,
  INTAKE_READ,
  INTAKE_WRITE,
  EXTRACTION_READ,
  EXTRACTION_RUN,
  VALIDATION_READ,
  VALIDATION_RUN,
  REVIEW_READ,
  REVIEW_ACTION,
  QUOTE_READ,
  QUOTE_ACTION,
  BOT_READ,
  BOT_ACTION,
  AUDIT_READ,
  ADMIN_SETTINGS_READ,
  CHANNEL_IDENTITY_ACTION,
  AI_WORK_ACTION,
  // OP-CAP-07D: internal/service permission for the AI-worker result intake endpoint.
  AI_RESULT_INTAKE,
  // OP-CAP-16I: platform/admin runtime governance — read tenant plan/feature entitlement status.
  RUNTIME_ENTITLEMENT_READ,
  // OP-CAP-16I: platform/admin runtime governance — create/update plans and feature entitlements.
  // Not for general operators.
  RUNTIME_ENTITLEMENT_MANAGE,
  // OP-CAP-17A: read-only access to deterministic document trust runs/signals.
  TRUST_READ
}
