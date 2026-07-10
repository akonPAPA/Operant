package com.orderpilot.security;

import com.orderpilot.security.production.GatewayHeaderAuthProductionRules;
import com.orderpilot.security.production.ProductionLikeProfiles;
import java.util.Locale;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * OP-CAP-43D — Production profile guard for gateway-header authentication.
 *
 * <p>OP-CAP-43C proved the application-level HMAC verifier
 * ({@link GatewayHeaderSignatureVerifier} + {@code ApiHeaderAuthenticationFilter}) fails closed:
 * missing/invalid/expired signatures, permission tampering, and a valid signature without the
 * required permission are all rejected. That guarantee, however, only holds when the deployment is
 * actually configured to require a signature with a real shared secret.
 *
 * <p>The dev/test convenience mode ({@code enabled=true, signature-required=false}) deliberately
 * trusts client-supplied authority headers without a signature so local MockMvc/dev flows work
 * without a gateway. If that same mode reaches a production deployment — or production runs with a
 * blank shared secret — the header-trust security model is silently disabled and any client could
 * self-grant tenant/actor/permission authority. The verifier code is sound; the risk is purely a
 * misconfiguration at deploy time.
 *
 * <p>This guard closes that gap by failing application startup (fail-closed) when a production-like
 * profile is active and gateway-header authentication is missing, unsigned, or otherwise unsafe. It is a narrow
 * startup/config validator only: no new framework, no runtime/DB dependency, no external call, and
 * it never logs or echoes the shared-secret value.
 *
 * <p>It is intentionally a component-scanned bean (not imported by the {@code @WebMvcTest} security
 * slices), so the existing OP-CAP-43A/43B/43C route/header-auth tests are unaffected; in non-prod
 * profiles (including {@code test}/{@code integration-test}) it is a no-op.
 */
@Component
public class GatewayHeaderAuthProductionGuard implements InitializingBean {

  /** @deprecated use {@link ProductionLikeProfiles#PRODUCTION_LIKE_PROFILES} */
  @Deprecated
  static final java.util.Set<String> PRODUCTION_LIKE_PROFILES =
      ProductionLikeProfiles.PRODUCTION_LIKE_PROFILES;

  private final Environment environment;
  private final boolean enabled;
  private final boolean signatureRequired;
  private final String sharedSecret;
  private final String replayStore;
  private final boolean singleInstanceReplayStoreAllowedInProduction;

  GatewayHeaderAuthProductionGuard(
      Environment environment,
      @Value("${orderpilot.security.gateway-header-auth.enabled:false}") boolean enabled,
      @Value("${orderpilot.security.gateway-header-auth.signature-required:true}") boolean signatureRequired,
      @Value("${orderpilot.security.gateway-header-auth.shared-secret:}") String sharedSecret,
      @Value("${orderpilot.security.gateway-header-auth.replay-store:memory}") String replayStore,
      @Value("${orderpilot.security.gateway-header-auth.allow-single-instance-replay-store-in-production:false}")
          boolean singleInstanceReplayStoreAllowedInProduction) {
    this.environment = environment;
    this.enabled = enabled;
    this.signatureRequired = signatureRequired;
    this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
    this.replayStore = replayStore == null ? "" : replayStore.trim().toLowerCase(Locale.ROOT);
    this.singleInstanceReplayStoreAllowedInProduction = singleInstanceReplayStoreAllowedInProduction;
  }

  @Override
  public void afterPropertiesSet() {
    if (!ProductionLikeProfiles.isActive(environment)) {
      return;
    }
    GatewayHeaderAuthProductionRules.validateProductionGatewayConfiguration(
        enabled,
        signatureRequired,
        sharedSecret,
        replayStore,
        singleInstanceReplayStoreAllowedInProduction);
  }
}
