package com.orderpilot.security;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Production intake guard: pass-through malware scanning and missing external scanner config are not
 * allowed in production-like profiles unless explicitly documented.
 */
@Component
public class ProductionIntakeSecurityGuard implements InitializingBean {

  private final Environment environment;
  private final String scanMode;
  private final String externalEndpoint;
  private final boolean allowPassThroughInProduction;

  ProductionIntakeSecurityGuard(
      Environment environment,
      @Value("${orderpilot.intake.malware-scan.mode:pass-through}") String scanMode,
      @Value("${orderpilot.intake.malware-scan.external-endpoint:}") String externalEndpoint,
      @Value("${orderpilot.intake.malware-scan.allow-pass-through-in-production:false}")
          boolean allowPassThroughInProduction) {
    this.environment = environment;
    this.scanMode = scanMode == null ? "pass-through" : scanMode.trim().toLowerCase(Locale.ROOT);
    this.externalEndpoint = externalEndpoint == null ? "" : externalEndpoint.trim();
    this.allowPassThroughInProduction = allowPassThroughInProduction;
  }

  @Override
  public void afterPropertiesSet() {
    if (!isProductionLikeProfileActive()) {
      return;
    }
    if ("pass-through".equals(scanMode)) {
      if (!allowPassThroughInProduction) {
        throw new IllegalStateException(
            "intake malware-scan mode must be external in production-like profiles "
                + "(set orderpilot.intake.malware-scan.mode=external and configure "
                + "orderpilot.intake.malware-scan.external-endpoint)");
      }
      return;
    }
    if ("external".equals(scanMode)) {
      if (externalEndpoint.isBlank()) {
        throw new IllegalStateException(
            "intake malware-scan external-endpoint must be configured when mode=external in production");
      }
      return;
    }
    throw new IllegalStateException(
        "unsupported intake malware-scan mode in production: " + scanMode);
  }

  private boolean isProductionLikeProfileActive() {
    return Arrays.stream(environment.getActiveProfiles())
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .anyMatch(GatewayHeaderAuthProductionGuard.PRODUCTION_LIKE_PROFILES::contains);
  }
}
