package com.orderpilot.application.services.channel;

public record VerificationResult(boolean accepted, String status, String reason) {
  public static VerificationResult accepted(String reason) {
    return new VerificationResult(true, "ACCEPTED", reason);
  }

  public static VerificationResult skippedLocalDev(String reason) {
    return new VerificationResult(true, "SKIPPED_LOCAL_DEV", reason);
  }

  public static VerificationResult rejected(String reason) {
    return new VerificationResult(false, "REJECTED", reason);
  }
}
