package com.orderpilot.application.services.channel;

/**
 * Stable fail-closed denial for public webhook authentication / connection-authority failures.
 *
 * <p>Mapped to HTTP 401 with a single redacted machine-readable code. The message must never reveal
 * whether a connection, tenant, or secret exists, and must never echo signature/payload material.
 */
public final class WebhookAuthenticationException extends RuntimeException {
  public static final String CODE = "WEBHOOK_AUTHENTICATION_FAILED";
  public static final String SAFE_MESSAGE = "Webhook authentication failed";

  public WebhookAuthenticationException() {
    super(SAFE_MESSAGE);
  }
}
