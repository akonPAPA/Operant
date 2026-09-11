package com.orderpilot.aibot.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.orderpilot.security.ApiPermission;
import com.orderpilot.security.ApiRouteSecurityPolicy;
import org.junit.jupiter.api.Test;

class AibotRouteSecurityPolicyTest {
  private final ApiRouteSecurityPolicy policy = new ApiRouteSecurityPolicy();

  @Test
  void createRequiresBotCreate() {
    assertThat(policy.requiredPermissionFor("POST", "/api/v1/bots"))
        .contains(ApiPermission.BOT_CREATE);
  }

  @Test
  void generateRequiresEditDraft() {
    assertThat(policy.requiredPermissionFor("POST", "/api/v1/bots/bot_x/versions/1/generate"))
        .contains(ApiPermission.BOT_EDIT_DRAFT);
  }

  @Test
  void previewRequiresBotPreview() {
    assertThat(policy.requiredPermissionFor("POST", "/api/v1/bots/bot_x/versions/1/preview"))
        .contains(ApiPermission.BOT_PREVIEW);
  }

  @Test
  void readsRequireBotRead() {
    assertThat(policy.requiredPermissionFor("GET", "/api/v1/bots/bot_x/versions/1"))
        .contains(ApiPermission.BOT_READ);
    assertThat(policy.requiredPermissionFor("GET", "/api/v1/ai-jobs/job_x"))
        .contains(ApiPermission.BOT_READ);
  }

  @Test
  void botReadDoesNotGrantCreate() {
    assertThat(policy.requiredPermissionFor("POST", "/api/v1/bots"))
        .isPresent()
        .get()
        .isNotEqualTo(ApiPermission.BOT_READ);
  }
}
