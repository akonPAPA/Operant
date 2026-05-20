package com.orderpilot.application.services.channel;

public record WebhookSignatureVerificationResult(
    boolean accepted,
    WebhookVerificationMode mode,
    String providerName,
    String status) {}
