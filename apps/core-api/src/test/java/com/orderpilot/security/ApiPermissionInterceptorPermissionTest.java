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

  // --- /api/v1/channel-gateway/messages requires INTAKE_WRITE ---

  @Test
  void channelGatewayMessagesPostWithIntakeWriteSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-gateway/messages");
    req.addHeader("X-OrderPilot-Permissions", "INTAKE_WRITE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void channelGatewayMessagesPostWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-gateway/messages");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("INTAKE_WRITE");
  }

  @Test
  void channelGatewayWhatsappWebhookPostIsUnguardedForExternalProvider() throws Exception {
    // Webhook path from external provider — must remain unguarded (signature-verified internally).
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/channel-gateway/whatsapp/webhook");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  // --- OP-CAP-07A /api/v1/ai-work: GET requires REVIEW_READ, mutations require AI_WORK_ACTION ---

  @Test
  void aiWorkGetWithReviewReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/ai-work/suggestions");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiWorkGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/ai-work/suggestions");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_READ");
  }

  @Test
  void aiWorkCreateWithAiWorkActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ai-work/suggestions");
    req.addHeader("X-OrderPilot-Permissions", "AI_WORK_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiWorkCreateWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ai-work/suggestions");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("AI_WORK_ACTION");
  }

  @Test
  void aiWorkAcceptWithReviewReadAloneIsRejected() throws Exception {
    // Read permission must NOT be sufficient to accept/reject an AI suggestion.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ai-work/suggestions/some-id/accept");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("AI_WORK_ACTION");
  }

  @Test
  void aiWorkRejectWithAiWorkActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/ai-work/suggestions/some-id/reject");
    req.addHeader("X-OrderPilot-Permissions", "AI_WORK_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  // --- OP-CAP-07D /api/v1/internal/ai-processing-results requires AI_RESULT_INTAKE ---

  @Test
  void aiResultIntakePostWithIntakePermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/internal/ai-processing-results");
    req.addHeader("X-OrderPilot-Permissions", "AI_RESULT_INTAKE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiResultIntakePostWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/internal/ai-processing-results");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("AI_RESULT_INTAKE");
  }

  @Test
  void aiResultIntakeWithReviewReadAloneIsRejected() throws Exception {
    // A generic read permission must NOT be sufficient for the service intake boundary.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/internal/ai-processing-results");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("AI_RESULT_INTAKE");
  }

  // --- OP-CAP-07E /api/v1/internal/extractions/{id}/validate requires VALIDATION_RUN ---

  @Test
  void aiExtractionValidateWithValidationRunSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/internal/extractions/some-id/validate");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_RUN");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiExtractionValidateWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/internal/extractions/some-id/validate");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_RUN");
  }

  @Test
  void aiExtractionValidationReadRequiresExtractionRead() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/extractions/some-id/validation");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("EXTRACTION_READ");
  }

  // --- unrelated paths are not affected ---

  @Test
  void botRuntimeMutationStillRequiresBotAction() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/bot-runtime/configurations/id");
    req.addHeader("X-OrderPilot-Permissions", "BOT_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }
}
