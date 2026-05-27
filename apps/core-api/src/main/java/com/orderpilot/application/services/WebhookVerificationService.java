package com.orderpilot.application.services;

import java.util.Map;

public interface WebhookVerificationService {
  WebhookVerificationResult verify(String provider, String payload, Map<String, String> headers);

  record WebhookVerificationResult(boolean accepted, String mode, String reason) {}
}
