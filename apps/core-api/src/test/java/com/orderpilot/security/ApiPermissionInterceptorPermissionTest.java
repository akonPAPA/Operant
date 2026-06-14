package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

  // --- OP-CAP-13B /api/v1/validations advisory handoff trigger requires VALIDATION_RUN ---

  @Test
  void advisoryHandoffPostWithValidationRunSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/advisory-handoff/123e4567-e89b-12d3-a456-426614174000");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_RUN");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void advisoryHandoffPostWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/advisory-handoff/123e4567-e89b-12d3-a456-426614174000");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_RUN");
  }

  // --- OP-CAP-14A /api/v1/validations review GET requires VALIDATION_READ ---

  @Test
  void validationRunReviewGetWithValidationReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationRunReviewGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  @Test
  void validationExtractionReviewGetWithValidationReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/extractions/123e4567-e89b-12d3-a456-426614174000/review");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationExtractionReviewGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/extractions/123e4567-e89b-12d3-a456-426614174000/review");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  // --- OP-CAP-14C /api/v1/validations/{id}/review commands require REVIEW_ACTION (not VALIDATION_RUN) ---

  @Test
  void validationReviewCorrectionWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/corrections");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewCorrectionWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/corrections");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void validationReviewCorrectionWithValidationRunAloneIsRejected() throws Exception {
    // VALIDATION_RUN triggers the engine; it must NOT be sufficient for an operator review command.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/corrections");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_RUN");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void validationReviewIssueResolutionWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/issues/abc/resolution");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewApprovalRequestWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/approval-requests");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void validationReviewDraftQuoteWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draft-quote");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewDraftOrderWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draft-order");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void validationReviewDraftStatusGetWithValidationReadSucceeds() throws Exception {
    // OP-CAP-15B: draft-status is a read — GET under /api/v1/validations requires VALIDATION_READ.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draft-status");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewDraftStatusGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draft-status");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  @Test
  void validationReviewDraftQuoteWithValidationRunAloneIsRejected() throws Exception {
    // VALIDATION_RUN (engine trigger) must NOT be sufficient to create a draft from a review.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draft-quote");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_RUN");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void validationReviewDraftabilityGetWithValidationReadSucceeds() throws Exception {
    // OP-CAP-15C: per-line draftability hints are a read — GET under /api/v1/validations needs VALIDATION_READ.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draftability");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void validationReviewDraftabilityGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/123e4567-e89b-12d3-a456-426614174000/review/draftability");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  @Test
  void reviewDraftQueueGetWithValidationReadSucceeds() throws Exception {
    // OP-CAP-15C: review-origin draft queue is a read — GET under /api/v1/validations needs VALIDATION_READ.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/validations/review-drafts");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void reviewDraftQueueGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/validations/review-drafts");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  @Test
  void reviewDraftRecentRemediationRollupGetWithValidationReadSucceeds() throws Exception {
    // OP-CAP-15J: recent remediation rollup tile is a read — GET under /api/v1/validations needs VALIDATION_READ.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/validations/review-drafts/remediation-rollup");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void reviewDraftRecentRemediationRollupGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/validations/review-drafts/remediation-rollup");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  @Test
  void reviewDraftRemediationLineageGetWithValidationReadSucceeds() throws Exception {
    // OP-CAP-15H: remediation lineage detail is a read — GET under /api/v1/validations needs VALIDATION_READ.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/review-drafts/QUOTE/123e4567-e89b-12d3-a456-426614174000/remediation-lineage");
    req.addHeader("X-OrderPilot-Permissions", "VALIDATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void reviewDraftRemediationLineageGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/validations/review-drafts/QUOTE/123e4567-e89b-12d3-a456-426614174000/remediation-lineage");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_READ");
  }

  @Test
  void validationEngineTriggerStillRequiresValidationRunNotReviewAction() throws Exception {
    // A non-review validations mutation (advisory handoff) must remain VALIDATION_RUN-guarded.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/validations/advisory-handoff/123e4567-e89b-12d3-a456-426614174000");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("VALIDATION_RUN");
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

  // --- OP-CAP-11F /api/v1/pilot: GET requires ANALYTICS_READ, mutations require REVIEW_ACTION ---

  @Test
  void pilotMetricsGetWithAnalyticsReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pilot/metrics");
    req.addHeader("X-OrderPilot-Permissions", "ANALYTICS_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void pilotExceptionsGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pilot/metrics/exceptions");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_READ");
  }

  @Test
  void pilotEvidenceReportGetWithAnalyticsReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pilot/evidence-report");
    req.addHeader("X-OrderPilot-Permissions", "ANALYTICS_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void pilotEvidenceReportGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pilot/evidence-report");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_READ");
  }

  @Test
  void pilotDemoScenariosGetWithAnalyticsReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pilot/demo-scenarios");
    req.addHeader("X-OrderPilot-Permissions", "ANALYTICS_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void pilotDemoScenariosGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pilot/demo-scenarios");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_READ");
  }

  @Test
  void pilotShadowRunPostWithReviewActionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/pilot/shadow-runs");
    req.addHeader("X-OrderPilot-Permissions", "REVIEW_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void pilotShadowRunPostWithAnalyticsReadAloneIsRejected() throws Exception {
    // Read permission must NOT be sufficient to record a shadow run.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/pilot/shadow-runs");
    req.addHeader("X-OrderPilot-Permissions", "ANALYTICS_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  @Test
  void pilotCorrectionPostWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/pilot/shadow-runs/some-id/corrections");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("REVIEW_ACTION");
  }

  // --- OP-CAP-17B counterparty trust reads require TRUST_READ (shared /api/v1/trust prefix) ---

  @Test
  void counterpartyTrustProfileGetWithTrustReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/counterparties/some-id");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void counterpartyTrustProfileGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/counterparties/some-id/signals");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_READ");
  }

  // --- OP-CAP-17D trust risk decisions: GET requires TRUST_READ, evaluate requires
  //     TRUST_RISK_EVALUATE, override requires the stronger TRUST_RISK_OVERRIDE ---

  @Test
  void trustRiskDecisionGetWithTrustReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/risk-decisions/some-id");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void trustRiskEvaluateWithEvaluatePermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/risk-decisions/evaluate");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_RISK_EVALUATE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void trustRiskEvaluateWithTrustReadAloneIsRejected() throws Exception {
    // A read permission must NOT be sufficient to write-through a risk evaluation.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/risk-decisions/evaluate");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_RISK_EVALUATE");
  }

  @Test
  void trustRiskOverrideWithOverridePermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/risk-decisions/some-id/override");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_RISK_OVERRIDE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void trustRiskOverrideWithEvaluatePermissionAloneIsRejected() throws Exception {
    // Evaluate must NOT be sufficient to override a decision (override is the stronger permission).
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/risk-decisions/some-id/override");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_RISK_EVALUATE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_RISK_OVERRIDE");
  }

  // --- OP-CAP-17E trust analytics: GET reads require TRUST_ANALYTICS_READ, the bounded rebuild
  //     requires the stronger TRUST_ANALYTICS_REBUILD (and not the generic TRUST_READ prefix) ---

  @Test
  void trustAnalyticsReviewQueueGetWithAnalyticsReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/analytics/review-queue");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_ANALYTICS_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void trustAnalyticsGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/analytics/risk-distribution");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_ANALYTICS_READ");
  }

  @Test
  void trustAnalyticsReadWithGenericTrustReadAloneIsRejected() throws Exception {
    // The generic TRUST_READ must NOT satisfy the dedicated analytics read permission.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/analytics/outstanding-debt");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_ANALYTICS_READ");
  }

  @Test
  void trustAnalyticsRebuildWithRebuildPermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/analytics/rebuild");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_ANALYTICS_REBUILD");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void trustAnalyticsRebuildWithAnalyticsReadAloneIsRejected() throws Exception {
    // Read must NOT be sufficient to trigger a rebuild (rebuild is the stronger permission).
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/analytics/rebuild");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_ANALYTICS_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_ANALYTICS_REBUILD");
  }

  // --- OP-CAP-17F AI memory governance: read/write/invalidate have distinct permissions; generic
  //     TRUST_READ is insufficient for write/invalidate. Runtime traces have dedicated read/write. ---

  @Test
  void aiMemoryGetWithMemoryReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-memory?namespace=PRODUCT_ALIAS_HINT");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiMemoryGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-memory/some-id");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_READ");
  }

  @Test
  void aiMemoryCreateWithMemoryWriteSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_WRITE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiMemoryCreateWithTrustReadAloneIsRejected() throws Exception {
    // Generic TRUST_READ must NOT be sufficient to write AI memory.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_WRITE");
  }

  @Test
  void aiMemoryInvalidateWithInvalidatePermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/some-id/invalidate");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_INVALIDATE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiMemoryInvalidateWithMemoryWriteAloneIsRejected() throws Exception {
    // Write must NOT be sufficient to invalidate (invalidate is its own governance permission).
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/some-id/invalidate");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_INVALIDATE");
  }

  @Test
  void aiRuntimeTraceWriteWithTraceWritePermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-runtime/traces");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_RUNTIME_TRACE_WRITE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiRuntimeTraceWriteWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-runtime/traces");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_RUNTIME_TRACE_WRITE");
  }

  @Test
  void aiRuntimeTraceGetWithTraceReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-runtime/traces");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_RUNTIME_TRACE_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiRuntimeTraceWriteWithMemoryWriteAloneIsRejected() throws Exception {
    // AI memory write must NOT be sufficient for the runtime-trace write boundary.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-runtime/traces");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_RUNTIME_TRACE_WRITE");
  }

  // --- OP-CAP-18 trust/AI event projector runtime: GET reads require TRUST_AI_EVENT_READ, processing
  //     (any non-GET) requires the stronger TRUST_AI_EVENT_PROCESS; generic TRUST_READ is insufficient ---

  @Test
  void aiEventsGetWithEventReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-events?status=PENDING");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_EVENT_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiEventsGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-events/some-id");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_EVENT_READ");
  }

  @Test
  void aiEventsProcessWithProcessPermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-events/process");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_EVENT_PROCESS");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void aiEventsProcessWithEventReadAloneIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-events/some-id/process");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_EVENT_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_EVENT_PROCESS");
  }

  @Test
  void aiEventsProcessWithGenericTrustReadIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-events/process");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_EVENT_PROCESS");
  }

  // --- OP-CAP-18 operator correction learning: read/write/approve/reject have distinct permissions ---

  @Test
  void operatorCorrectionsGetWithReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/operator-corrections");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_OPERATOR_CORRECTION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void operatorCorrectionsRecordWithWriteSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/operator-corrections");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_OPERATOR_CORRECTION_WRITE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void operatorCorrectionsRecordWithReadAloneIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/operator-corrections");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_OPERATOR_CORRECTION_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_OPERATOR_CORRECTION_WRITE");
  }

  @Test
  void operatorCorrectionApproveWithApprovePermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/operator-corrections/some-id/approve-learning");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_OPERATOR_CORRECTION_APPROVE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void operatorCorrectionApproveWithWriteAloneIsRejected() throws Exception {
    // Write must NOT be sufficient to approve learning (approval is its own permission).
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/operator-corrections/some-id/approve-learning");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_OPERATOR_CORRECTION_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_OPERATOR_CORRECTION_APPROVE");
  }

  @Test
  void operatorCorrectionRejectWithRejectPermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/operator-corrections/some-id/reject-learning");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_OPERATOR_CORRECTION_REJECT");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void operatorCorrectionApproveWithGenericTrustReadIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/operator-corrections/some-id/approve-learning");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_OPERATOR_CORRECTION_APPROVE");
  }

  // --- OP-CAP-19 Layer B advisory retrieval: POST query is a READ — requires TRUST_AI_MEMORY_READ,
  //     never the write/invalidate permissions ---

  @Test
  void advisoryRetrievalPostWithMemoryReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/advisory-retrieval");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void advisoryRetrievalPostWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/advisory-retrieval");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_READ");
  }

  @Test
  void advisoryRetrievalPostWithMemoryWriteAloneIsRejected() throws Exception {
    // Advisory retrieval is a read; the write permission must NOT satisfy it.
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/advisory-retrieval");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_READ");
  }

  // --- OP-CAP-19 Layer C evaluation harness: read/write/run have distinct permissions; generic AI-memory
  //     read/write never grants evaluation run ---

  @Test
  void evaluationCreateRunWithEvaluationWriteSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/evaluations/runs");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_WRITE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void evaluationCreateRunWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/trust/ai-memory/evaluations/runs");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_WRITE");
  }

  @Test
  void evaluationAddCaseWithEvaluationWriteSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/runs/some-id/cases");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_WRITE");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void evaluationExecuteWithEvaluationRunSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/runs/some-id/execute");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_RUN");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void evaluationExecuteWithEvaluationWriteAloneIsRejected() throws Exception {
    // Write must NOT be sufficient to execute a run (run is the stronger permission).
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/runs/some-id/execute");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_RUN");
  }

  @Test
  void evaluationExecuteWithGenericMemoryWriteIsRejected() throws Exception {
    // Generic AI-memory write must NOT grant evaluation execution.
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/runs/some-id/execute");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_RUN");
  }

  @Test
  void evaluationRunsGetWithEvaluationReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-memory/evaluations/runs");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void evaluationResultsGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/trust/ai-memory/evaluations/runs/some-id/results");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_READ");
  }

  @Test
  void evaluationReadWithGenericMemoryReadAloneIsRejected() throws Exception {
    // Generic AI-memory read must NOT grant the dedicated evaluation read permission.
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/trust/ai-memory/evaluations/runs");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_READ");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_READ");
  }

  // --- OP-CAP-20 Layer A advisory runtime assist: GET requires TRUST_AI_MEMORY_READ (same read
  //     permission as advisory retrieval), never a write permission ---

  @Test
  void advisoryAssistGetWithMemoryReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/trust/ai-memory/advisory-assist?contextType=TRUST_VALIDATION_REVIEW");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void advisoryAssistGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "GET", "/api/v1/trust/ai-memory/advisory-assist?contextType=TRUST_VALIDATION_REVIEW");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_READ");
  }

  // --- OP-CAP-20 Layer B bounded batch run: it executes, so it requires the strongest
  //     TRUST_AI_MEMORY_EVALUATION_RUN — never the weaker WRITE or a generic AI-memory write ---

  @Test
  void evaluationBatchRunWithRunPermissionSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/batch-runs");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_RUN");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void evaluationBatchRunWithEvaluationWriteAloneIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/batch-runs");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_EVALUATION_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_RUN");
  }

  @Test
  void evaluationBatchRunWithGenericMemoryWriteIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest(
        "POST", "/api/v1/trust/ai-memory/evaluations/batch-runs");
    req.addHeader("X-OrderPilot-Permissions", "TRUST_AI_MEMORY_WRITE");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("TRUST_AI_MEMORY_EVALUATION_RUN");
  }

  // --- OP-CAP-21: command center summary read requires ANALYTICS_READ ---

  @Test
  void commandCenterSummaryGetWithAnalyticsReadSucceeds() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/command-center/summary");
    req.addHeader("X-OrderPilot-Permissions", "ANALYTICS_READ");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }

  @Test
  void commandCenterSummaryGetWithoutPermissionIsRejected() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/command-center/summary");

    assertThatThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER))
        .isInstanceOf(TenantPolicyException.class)
        .hasMessageContaining("ANALYTICS_READ");
  }

  // --- unrelated paths are not affected ---

  @Test
  void botRuntimeMutationStillRequiresBotAction() throws Exception {
    MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/v1/bot-runtime/configurations/id");
    req.addHeader("X-OrderPilot-Permissions", "BOT_ACTION");

    assertThatNoException().isThrownBy(() -> interceptor.preHandle(req, new MockHttpServletResponse(), HANDLER));
  }
}