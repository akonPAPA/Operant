package com.orderpilot.security;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
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
 * profile is active and gateway-header authentication is enabled in an unsafe shape. It is a narrow
 * startup/config validator only: no new framework, no runtime/DB dependency, no external call, and
 * it never logs or echoes the shared-secret value.
 *
 * <p>It is intentionally a component-scanned bean (not imported by the {@code @WebMvcTest} security
 * slices), so the existing OP-CAP-43A/43B/43C route/header-auth tests are unaffected; in non-prod
 * profiles (including {@code test}/{@code integration-test}) it is a no-op.
 */
@Component
public class GatewayHeaderAuthProductionGuard implements InitializingBean {

  /**
   * Profile names treated as production-like. {@code staging} is included deliberately: a staging
   * environment is exposed and internet-adjacent, so it must enforce the same header-trust contract
   * as production rather than silently running in dev/test mode.
   */
  static final Set<String> PRODUCTION_LIKE_PROFILES = Set.of("prod", "production", "cloud", "staging");

  private final Environment environment;
  private final boolean enabled;
  private final boolean signatureRequired;
  private final String sharedSecret;

  GatewayHeaderAuthProductionGuard(
      Environment environment,
      @Value("${orderpilot.security.gateway-header-auth.enabled:false}") boolean enabled,
      @Value("${orderpilot.security.gateway-header-auth.signature-required:true}") boolean signatureRequired,
      @Value("${orderpilot.security.gateway-header-auth.shared-secret:}") String sharedSecret) {
    this.environment = environment;
    this.enabled = enabled;
    this.signatureRequired = signatureRequired;
    this.sharedSecret = sharedSecret == null ? "" : sharedSecret;
  }

  @Override
  public void afterPropertiesSet() {
    // Only production-like profiles enforce the contract; if header trust is off, the filter never
    // trusts client headers regardless of signature/secret, so there is nothing to guard.
    if (!isProductionLikeProfileActive() || !enabled) {
      return;
    }
    if (!signatureRequired) {
      throw new IllegalStateException(
          "gateway-header-auth signature-required must be true in production "
              + "(signature-required=false is dev/test only; a trusted gateway must HMAC-sign authority headers)");
    }
    if (sharedSecret.isBlank()) {
      // Never include the value — only the property name.
      throw new IllegalStateException(
          "gateway-header-auth shared-secret must be configured in production "
              + "(orderpilot.security.gateway-header-auth.shared-secret is blank/missing)");
    }
  }

  private boolean isProductionLikeProfileActive() {
    return Arrays.stream(environment.getActiveProfiles())
        .map(profile -> profile.toLowerCase(Locale.ROOT))
        .anyMatch(PRODUCTION_LIKE_PROFILES::contains);
  }
}
