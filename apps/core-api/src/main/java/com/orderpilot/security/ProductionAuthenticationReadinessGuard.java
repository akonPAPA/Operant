package com.orderpilot.security;

import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Documents and enforces production authentication readiness. Enterprise OIDC/SSO is not implemented
 * in this repository; production-like deployments must use gateway-signed header authentication with
 * {@link GatewayHeaderAuthProductionGuard}.
 */
@Component
public class ProductionAuthenticationReadinessGuard implements InitializingBean {

  static final String OIDC_RUNTIME_NOT_IMPLEMENTED = "OIDC_RUNTIME_NOT_IMPLEMENTED";

  private final Environment environment;
  private final boolean oidcEnabled;

  ProductionAuthenticationReadinessGuard(
      Environment environment,
      @Value("${orderpilot.security.oidc.enabled:false}") boolean oidcEnabled) {
    this.environment = environment;
    this.oidcEnabled = oidcEnabled;
  }

  @Override
  public void afterPropertiesSet() {
    if (!isProductionLikeProfileActive()) {
      return;
    }
    if (oidcEnabled) {
      throw new IllegalStateException(
          OIDC_RUNTIME_NOT_IMPLEMENTED
              + ": orderpilot.security.oidc.enabled=true cannot enable production authentication "
              + "until the BFF OIDC runtime is implemented");
    }
  }

  private boolean isProductionLikeProfileActive() {
    return Arrays.stream(environment.getActiveProfiles())
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .anyMatch(GatewayHeaderAuthProductionGuard.PRODUCTION_LIKE_PROFILES::contains);
  }
}
