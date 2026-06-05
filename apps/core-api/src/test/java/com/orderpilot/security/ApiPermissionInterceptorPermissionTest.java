package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.orderpilot.security.policy.TenantPolicyException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * OP-CAP-06D.1 unit tests for ApiPermissionInterceptor permission mapping.
 * Pure unit test — no Spring context required.
 */
class ApiPermissionInterceptorPermissionTest {
  private final ApiPermissionGuard guard = new ApiPermissionGuard();
  private final ApiPermissionInterceptor interceptor = new ApiPermissionInterceptor(guard);
  private static final Object HANDLER = new Object();

  // --- /api/v1/channel-identities read ---

  @Test
  void channelIdentitiesGetWithAdminSettingsReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/channel-identities");
    req.addHeader("X-OrderPilot-Permissions", "ADMIN_SETTINGS_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void channelIdentitiesGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/channel-identities");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ADMIN_SETTINGS_READ");
  }

  // --- /api/v1/channel-identities mutations require CHANNEL_IDENTITY_ACTION, not BOT_ACTION ---

  @Test
  void channelIdentitiesPostWithChannelIdentityActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-identities/some-id/link");
    req.addHeader("X-OrderPilot-Permissions", "CHANNEL_IDENTITY_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void channelIdentitiesPostWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-identities/some-id/block");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("CHANNEL_IDENTITY_ACTION");
  }

  @Test
  void channelIdentitiesPostWithBotActionAloneIsRejected() throws Exception {
    // BOT_ACTION must NOT be sufficient for channel-identity mutations (hardening requirement).
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-identities/some-id/needs-review");
    req.addHeader("X-OrderPilot-Permissions", "BOT_ACTION");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("CHANNEL_IDENTITY_ACTION");
  }

  @Test
  void channelIdentitiesUnlinkRequiresChannelIdentityAction() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-identities/some-id/unlink");
    req.addHeader("X-OrderPilot-Permissions", "CHANNEL_IDENTITY_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  // --- unrelated paths are not affected ---

  @Test
  void botRuntimeMutationStillRequiresBotAction() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/bot-runtime/configurations/id");
    req.addHeader("X-OrderPilot-Permissions", "BOT_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }
}
