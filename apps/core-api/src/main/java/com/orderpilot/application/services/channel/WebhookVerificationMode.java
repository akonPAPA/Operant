package com.orderpilot.application.services.channel;

public enum WebhookVerificationMode {
  DISABLED_FOR_LOCAL_DEV,
  SHARED_SECRET,
  SIGNATURE_HEADER,
  PROVIDER_SPECIFIC,
  NOT_CONFIGURED_STAGE_10E,
  DISABLED_FIXTURE_MODE,
  CONFIGURED_VERIFY_ONLY,
  FAILED
}
