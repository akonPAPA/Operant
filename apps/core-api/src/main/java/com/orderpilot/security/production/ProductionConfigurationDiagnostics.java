package com.orderpilot.security.production;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Builds a redacted, operator-safe view of production configuration for startup logs. */
public final class ProductionConfigurationDiagnostics {

  private ProductionConfigurationDiagnostics() {}

  public static Map<String, String> redactedSnapshot(
      boolean gatewayHeaderAuthEnabled,
      boolean gatewaySignatureRequired,
      String gatewayReplayStore,
      boolean demoRfqHandoffEnabled,
      boolean oidcEnabled,
      String publicApiBaseUrl,
      String publicWebBaseUrl,
      String corsAllowedOrigins,
      int serverPort,
      String malwareScanMode,
      String runtimeRateStore) {
    Map<String, String> snapshot = new LinkedHashMap<>();
    snapshot.put("gatewayHeaderAuth.enabled", Boolean.toString(gatewayHeaderAuthEnabled));
    snapshot.put("gatewayHeaderAuth.signatureRequired", Boolean.toString(gatewaySignatureRequired));
    snapshot.put("gatewayHeaderAuth.replayStore", sanitize(gatewayReplayStore));
    snapshot.put("gatewayHeaderAuth.sharedSecret", "[redacted]");
    snapshot.put("actorSigningSecret", "[redacted]");
    snapshot.put("datasource.password", "[redacted]");
    snapshot.put("demo.rfqHandoff.enabled", Boolean.toString(demoRfqHandoffEnabled));
    snapshot.put("security.oidc.enabled", Boolean.toString(oidcEnabled));
    snapshot.put("production.publicApiBaseUrl", sanitize(publicApiBaseUrl));
    snapshot.put("production.publicWebBaseUrl", sanitize(publicWebBaseUrl));
    snapshot.put("security.cors.allowedOrigins", sanitize(corsAllowedOrigins));
    snapshot.put("server.port", Integer.toString(serverPort));
    snapshot.put("intake.malwareScan.mode", sanitize(malwareScanMode));
    snapshot.put("runtime.rate.store", sanitize(runtimeRateStore));
    return Map.copyOf(snapshot);
  }

  public static String formatForLog(Map<String, String> snapshot) {
    StringBuilder builder = new StringBuilder("orderpilot production configuration (redacted): ");
    boolean first = true;
    for (Map.Entry<String, String> entry : snapshot.entrySet()) {
      if (!first) {
        builder.append("; ");
      }
      first = false;
      builder.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return builder.toString();
  }

  private static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return "<unset>";
    }
    return value.trim().toLowerCase(Locale.ROOT).contains("secret")
        ? "[redacted]"
        : value.trim();
  }
}
