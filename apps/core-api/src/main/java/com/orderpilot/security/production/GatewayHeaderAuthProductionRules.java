package com.orderpilot.security.production;

import java.util.Locale;

/**
 * Fail-closed production rules for trusted gateway header authentication. Shared by
 * {@link com.orderpilot.security.GatewayHeaderAuthProductionGuard} and
 * {@link ProductionConfigurationValidator}.
 */
public final class GatewayHeaderAuthProductionRules {

  private GatewayHeaderAuthProductionRules() {}

  public static void validateProductionGatewayConfiguration(
      boolean enabled,
      boolean signatureRequired,
      String sharedSecret,
      String replayStore,
      boolean singleInstanceReplayStoreAllowedInProduction) {
    if (!enabled) {
      throw new IllegalStateException(
          "gateway-header-auth must be enabled in production-like profiles "
              + "(unsigned/disabled header trust is not a production authentication mode)");
    }
    if (!signatureRequired) {
      throw new IllegalStateException(
          "gateway-header-auth signature-required must be true in production "
              + "(signature-required=false is dev/test only; a trusted gateway must HMAC-sign authority headers)");
    }
    String secret = sharedSecret == null ? "" : sharedSecret;
    if (secret.isBlank()) {
      throw new IllegalStateException(
          "gateway-header-auth shared-secret must be configured in production "
              + "(orderpilot.security.gateway-header-auth.shared-secret is blank/missing)");
    }
    ProductionInsecurePlaceholderValues.requireNonPlaceholder(
        "orderpilot.security.gateway-header-auth.shared-secret", secret);
    String store = replayStore == null ? "" : replayStore.trim().toLowerCase(Locale.ROOT);
    if (!"redis".equals(store) && !singleInstanceReplayStoreAllowedInProduction) {
      throw new IllegalStateException(
          "gateway-header-auth replay-store must be redis in production signed mode "
              + "(memory replay admission is single-instance only; set "
              + "orderpilot.security.gateway-header-auth.replay-store=redis or explicitly allow "
              + "single-instance production replay-store mode)");
    }
  }
}
