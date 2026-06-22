package com.orderpilot.application.services.channel;

public record VerificationResult(boolean accepted, String status, String reason) {
  public static VerificationResult accepted(String reason) {
    return new VerificationResult(true, "ACCEPTED", reason);
  }

  public static VerificationResult skippedLocalDev(String reason) {
    return new VerificationResult(true, "SKIPPED_LOCAL_DEV", reason);
  }

  /**
   * OP-CAP-42I: an explicit, real-cryptographically-verified accept (server-configured provider HMAC).
   * Distinct from {@link #skippedLocalDev} so a verified signature is never confused with a local-dev
   * skip, and distinct from a bare {@code ACCEPTED} so the verification authority (server-configured
   * secret, verify-only — no external write) is self-describing on the persisted event.
   */
  public static VerificationResult configuredVerifyOnly(String reason) {
    return new VerificationResult(true, "CONFIGURED_VERIFY_ONLY", reason);
  }

  public static VerificationResult rejected(String reason) {
    return new VerificationResult(false, "REJECTED", reason);
  }
}
