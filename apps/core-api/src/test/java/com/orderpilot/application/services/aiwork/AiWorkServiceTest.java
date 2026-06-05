package com.orderpilot.application.services.aiwork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.aiwork.AiWorkSourceType;
import com.orderpilot.domain.aiwork.AiWorkSuggestion;
import com.orderpilot.domain.aiwork.AiWorkSuggestionRepository;
import com.orderpilot.domain.aiwork.AiWorkType;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-07A AI Agent Work Layer service tests: advisory creation, idempotency, accept/reject
 * lifecycle, audit emission, and tenant isolation.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AiWorkService.class, DeterministicAiWorkProvider.class, AuditEventService.class, CoreConfiguration.class})
class AiWorkServiceTest {
  @Autowired private AiWorkService service;
  @Autowired private AiWorkSuggestionRepository repository;
  @Autowired private AuditEventRepository auditEventRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  private long countAudits(String action) {
    return auditEventRepository.findAll().stream()
        .filter(e -> action.equals(e.getAction())).count();
  }

  @Test
  void createSuggestionPersistsAdvisoryArtifactAndEmitsAudit() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID sourceId = UUID.randomUUID();

    AiWorkSuggestion created = service.createSuggestion(
        AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, sourceId,
        "Customer needs 10 brake pads", null, UUID.randomUUID());

    assertThat(created.getId()).isNotNull();
    assertThat(created.getStatus()).isEqualTo("GENERATED");
    assertThat(created.getStrategyVersion()).isEqualTo(DeterministicAiWorkProvider.STRATEGY_VERSION);
    assertThat(created.getGeneratedText()).contains("Summary (advisory)");
    assertThat(repository.count()).isEqualTo(1);
    assertThat(auditEventRepository.findAll()).extracting("action").contains("AI_WORK_SUGGESTION_CREATED");
  }

  @Test
  void auditMetadataContainsSafeFieldsOnlyNoGeneratedText() {
    TenantContext.setTenantId(UUID.randomUUID());
    AiWorkSuggestion created = service.createSuggestion(
        AiWorkType.CUSTOMER_REPLY_DRAFT, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(),
        "context", null, UUID.randomUUID());

    var event = auditEventRepository.findAll().stream()
        .filter(e -> "AI_WORK_SUGGESTION_CREATED".equals(e.getAction())
            && e.getMetadata().contains(created.getId().toString()))
        .findFirst().orElseThrow();
    String metadata = event.getMetadata();
    assertThat(metadata).contains("suggestionId");
    assertThat(metadata).contains("workType");
    assertThat(metadata).contains("sourceType");
    assertThat(metadata).contains("riskLevel");
    // Generated/customer-facing text must never be serialized into the audit metadata.
    assertThat(metadata).doesNotContain("Draft only");
  }

  @Test
  void createWithIdempotencyKeyDeduplicates() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID sourceId = UUID.randomUUID();
    // Audit events use REQUIRES_NEW and survive @DataJpaTest rollback, so assert on deltas not totals.
    long createdBefore = countAudits("AI_WORK_SUGGESTION_CREATED");

    AiWorkSuggestion first = service.createSuggestion(
        AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, sourceId, "ctx", "idem-1", null);
    long createdAfterFirst = countAudits("AI_WORK_SUGGESTION_CREATED");
    AiWorkSuggestion second = service.createSuggestion(
        AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, sourceId, "ctx", "idem-1", null);
    long createdAfterSecond = countAudits("AI_WORK_SUGGESTION_CREATED");

    assertThat(second.getId()).isEqualTo(first.getId());
    assertThat(repository.count()).isEqualTo(1);
    assertThat(createdAfterFirst - createdBefore).isEqualTo(1);
    assertThat(createdAfterSecond - createdAfterFirst).isZero();
  }

  @Test
  void acceptChangesStatusEmitsAuditAndIsIdempotent() {
    TenantContext.setTenantId(UUID.randomUUID());
    UUID operator = UUID.randomUUID();
    AiWorkSuggestion created = service.createSuggestion(
        AiWorkType.NEXT_ACTION_SUGGESTION, AiWorkSourceType.QUOTE, UUID.randomUUID(), "ctx", null, null);

    long acceptedBefore = countAudits("AI_WORK_SUGGESTION_ACCEPTED");
    AiWorkSuggestion accepted = service.accept(created.getId(), operator, "looks correct");
    assertThat(accepted.getStatus()).isEqualTo("ACCEPTED");
    assertThat(accepted.getDecidedByUserId()).isEqualTo(operator);
    long acceptedAfterFirst = countAudits("AI_WORK_SUGGESTION_ACCEPTED");

    // Idempotent: repeating accept does not emit a second audit event.
    service.accept(created.getId(), operator, "again");
    long acceptedAfterSecond = countAudits("AI_WORK_SUGGESTION_ACCEPTED");

    assertThat(acceptedAfterFirst - acceptedBefore).isEqualTo(1);
    assertThat(acceptedAfterSecond - acceptedAfterFirst).isZero();
  }

  @Test
  void rejectStoresReasonAndEmitsAudit() {
    TenantContext.setTenantId(UUID.randomUUID());
    AiWorkSuggestion created = service.createSuggestion(
        AiWorkType.VALIDATION_EXPLANATION, AiWorkSourceType.OPERATOR_REVIEW, UUID.randomUUID(), "ctx", null, null);

    AiWorkSuggestion rejected = service.reject(created.getId(), UUID.randomUUID(), "not relevant");

    assertThat(rejected.getStatus()).isEqualTo("REJECTED");
    assertThat(rejected.getDecisionReason()).isEqualTo("not relevant");
    assertThat(auditEventRepository.findAll()).extracting("action").contains("AI_WORK_SUGGESTION_REJECTED");
  }

  @Test
  void acceptingAnAlreadyRejectedSuggestionIsBlocked() {
    TenantContext.setTenantId(UUID.randomUUID());
    AiWorkSuggestion created = service.createSuggestion(
        AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, UUID.randomUUID(), "ctx", null, null);
    service.reject(created.getId(), UUID.randomUUID(), "rejected first");

    assertThatThrownBy(() -> service.accept(created.getId(), UUID.randomUUID(), "too late"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already rejected");
  }

  @Test
  void tenantACannotReadTenantBSuggestion() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    AiWorkSuggestion created = service.createSuggestion(
        AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, UUID.randomUUID(), "ctx", null, null);

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> service.getSuggestion(created.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("AI work suggestion not found");
  }

  @Test
  void listForSourceReturnsOnlyMatchingTenantScopedSuggestions() {
    UUID tenantA = UUID.randomUUID();
    UUID sharedSourceId = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    service.createSuggestion(AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, sharedSourceId, "a", null, null);

    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    service.createSuggestion(AiWorkType.REQUEST_SUMMARY, AiWorkSourceType.CHANNEL_MESSAGE, sharedSourceId, "b", null, null);

    var tenantBResults = service.listForSource(AiWorkSourceType.CHANNEL_MESSAGE, sharedSourceId);
    assertThat(tenantBResults).hasSize(1);
    assertThat(tenantBResults.get(0).getTenantId()).isEqualTo(tenantB);
  }
}
