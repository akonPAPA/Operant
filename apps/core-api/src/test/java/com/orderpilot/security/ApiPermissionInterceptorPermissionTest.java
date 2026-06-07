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

  // --- OP-CAP-09D /api/v1/workspace draft review queues + product picker: GET requires REVIEW_READ ---

  @Test
  void draftQuoteReviewQueueGetWithReviewReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/draft-quotes/review-queue");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void draftQuoteReviewQueueGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/draft-quotes/review-queue");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_READ");
  }

  @Test
  void draftOrderReviewQueueGetWithReviewReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/draft-orders/review-queue");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void workspaceProductSearchGetWithReviewReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/products/search?q=brk");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void workspaceProductSearchGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/products/search?q=brk");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_READ");
  }

  // --- OP-CAP-09A /api/v1/validation-review: GET requires REVIEW_READ, mutations (incl. prepare-draft) require REVIEW_ACTION ---

  @Test
  void validationReviewGetWithReviewReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/validation-review");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/validation-review");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_READ");
  }

  @Test
  void validationReviewPrepareDraftWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/validation-review/some-id/prepare-draft");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewPrepareDraftWithReviewReadAloneIsRejected() throws Exception {
    // Read-only review permission must NOT be sufficient to prepare a draft.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/validation-review/some-id/prepare-draft");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void validationReviewPrepareDraftWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/validation-review/some-id/prepare-draft");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  // --- OP-CAP-09B /api/v1/workspace/draft-quotes|draft-orders: GET requires REVIEW_READ, line PATCH / mark-ready require REVIEW_ACTION ---

  @Test
  void workspaceDraftQuoteReviewGetWithReviewReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/draft-quotes/some-id/review");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void workspaceDraftQuoteReviewGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/workspace/draft-quotes/some-id/review");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_READ");
  }

  @Test
  void workspaceDraftQuoteLinePatchWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/v1/workspace/draft-quotes/some-id/lines/line-id");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void workspaceDraftQuoteLinePatchWithReviewReadAloneIsRejected() throws Exception {
    // Read-only permission must NOT be sufficient to correct a draft line.
    MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/v1/workspace/draft-quotes/some-id/lines/line-id");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void workspaceDraftQuoteMarkReadyWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/workspace/draft-quotes/some-id/mark-ready");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void workspaceDraftOrderMarkReadyWithReviewReadAloneIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/workspace/draft-orders/some-id/mark-ready");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void workspaceDraftOrderLinePatchWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/api/v1/workspace/draft-orders/some-id/lines/line-id");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  // --- unrelated paths are not affected ---

  @Test
  void botRuntimeMutationStillRequiresBotAction() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/bot-runtime/configurations/id");
    req.addHeader("X-OrderPilot-Permissions", "BOT_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }
}
