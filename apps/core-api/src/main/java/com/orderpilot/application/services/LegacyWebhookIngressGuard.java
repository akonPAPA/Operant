package com.orderpilot.application.services;

import com.orderpilot.common.errors.NotFoundException;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-closed boundary for the pre-connection webhook routes.
 *
 * <p>Those legacy routes derive tenant scope from {@code X-Tenant-Id}; that is acceptable only for
 * explicit local/test fixtures. Production provider ingress must use the connection-bound channel
 * webhook routes, where the backend loads a server-owned connection for the tenant and verifies the
 * provider before persisting anything.
 */
@Component
public class LegacyWebhookIngressGuard {
  private static final Profiles LOCAL_OR_TEST = Profiles.of("local", "dev", "test");
  private final boolean allowed;

  public LegacyWebhookIngressGuard(Environment environment) {
    this.allowed = environment != null && environment.acceptsProfiles(LOCAL_OR_TEST);
  }

  public void requireLocalOrTest() {
    if (!allowed) {
      throw new NotFoundException("Webhook route not found");
    }
  }
}
