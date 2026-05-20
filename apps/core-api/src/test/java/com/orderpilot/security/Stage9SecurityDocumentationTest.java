package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage9SecurityDocumentationTest {
  private static final Path REPO_ROOT = Path.of("../..").normalize();

  @Test
  void requiredSecurityDocsExistAndContainKeyTerms() throws Exception {
    assertDoc("docs/security/THREAT_MODEL.md", "tenant isolation", "audit", "threat model", "no direct DB writes");
    assertDoc("docs/security/AI_AND_BOT_GOVERNANCE.md", "AI is advisory", "approval", "audit", "Bot Runtime Lite");
    assertDoc("docs/security/SECURITY_VERIFICATION_CHECKLIST.md", "tenant isolation", "audit", "approval", "webhook");
    assertDoc("docs/runbooks/SECURITY_REVIEW_RUNBOOK.md", "mvn clean test", "hardcoded secrets", "audit-critical");
    assertDoc("docs/investor/INVESTOR_DEMO_SCRIPT_CORE_V1.md", "Telegram RFQ", "mismatch -16", "analytics summary", "security");
    assertDoc("docs/investor/DEMO_DATASET_CORE_V1.md", "opening stock = 150", "sale = 34", "actual stock = 100");
  }

  @Test
  void noPublicAuditMutationControllerExists() throws Exception {
    String controllers = Files.readString(Path.of("src/main/java/com/orderpilot/api/rest/HealthController.java"))
        + Files.readString(Path.of("src/main/java/com/orderpilot/api/rest/BotTelegramWebhookController.java"))
        + Files.readString(Path.of("src/main/java/com/orderpilot/api/rest/ReconciliationController.java"))
        + Files.readString(Path.of("src/main/java/com/orderpilot/api/rest/CommerceAnalyticsController.java"));
    assertThat(controllers).doesNotContain("@RequestMapping(\"/api/v1/audit");
    assertThat(controllers).doesNotContain("@DeleteMapping(\"/api/v1/audit");
    assertThat(controllers).doesNotContain("@PatchMapping(\"/api/v1/audit");
  }

  private void assertDoc(String path, String... terms) throws Exception {
    String content = Files.readString(REPO_ROOT.resolve(path));
    assertThat(content).isNotBlank();
    for (String term : terms) {
      assertThat(content).containsIgnoringCase(term);
    }
  }
}
