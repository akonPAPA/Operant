package com.orderpilot.application.services.channel;

import com.orderpilot.security.production.ProductionLikeProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Server-owned webhook verification / fixture authority.
 *
 * <p>Unsigned fixture / local-dev intake is allowed only when the deployment is not production-like
 * and either {@code orderpilot.webhook.fixture-mode-enabled=true} or a local/dev/test Spring profile
 * is active. Client headers never grant fixture authority.
 */
@Component
public class WebhookVerificationAuthority {
  private static final Profiles LOCAL_OR_TEST = Profiles.of("local", "dev", "test");

  private final boolean fixtureModeEnabled;
  private final boolean productionLike;
  private final boolean localOrTestProfile;

  @Autowired
  public WebhookVerificationAuthority(
      Environment environment,
      @Value("${orderpilot.webhook.fixture-mode-enabled:false}") boolean fixtureModeEnabled) {
    this.fixtureModeEnabled = fixtureModeEnabled;
    this.productionLike = ProductionLikeProfiles.isActive(environment);
    this.localOrTestProfile = environment != null && environment.acceptsProfiles(LOCAL_OR_TEST);
  }

  /** Direct construction for unit tests. */
  public static WebhookVerificationAuthority forTests(boolean allowUnsignedFixture, boolean productionLike) {
    return new WebhookVerificationAuthority(allowUnsignedFixture, productionLike, allowUnsignedFixture && !productionLike);
  }

  private WebhookVerificationAuthority(
      boolean fixtureModeEnabled, boolean productionLike, boolean localOrTestProfile) {
    this.fixtureModeEnabled = fixtureModeEnabled;
    this.productionLike = productionLike;
    this.localOrTestProfile = localOrTestProfile;
  }

  /** Server-owned fixture / local-dev unsigned intake. Never derived from request headers. */
  public boolean allowsUnsignedFixtureIntake() {
    if (productionLike) {
      return false;
    }
    return fixtureModeEnabled || localOrTestProfile;
  }

  public boolean isFixtureModeEnabled() {
    return fixtureModeEnabled;
  }

  public boolean isProductionLike() {
    return productionLike;
  }
}
