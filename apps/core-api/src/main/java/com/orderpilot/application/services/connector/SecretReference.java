package com.orderpilot.application.services.connector;

import java.time.Instant;

public record SecretReference(String secretReferenceId, boolean configured, Instant lastUpdatedAt) {
  public static SecretReference unconfigured() {
    return new SecretReference(null, false, null);
  }
}
