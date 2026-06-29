package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderpilot.api.dto.Stage11EDtos.QuoteHandoffCommand;
import com.orderpilot.api.dto.Stage11EDtos.ChangeRequestDraftCommand;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.ChangeRequestRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.OutboxEventRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({QuoteExternalWritePreparationService.class, QuoteHandoffSnapshotService.class, QuoteHandoffReadinessService.class, ChangeRequestService.class, AuditEventService.class, JsonSupport.class, CoreConfiguration.class, QuoteHandoffSnapshotServiceTest.JacksonConfig.class})
class QuoteHandoffSnapshotServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private QuoteHandoffSnapshotService service;
  @Autowired private QuoteExternalWritePreparationService preparationService;
  @Autowired private DraftQuoteRepository quoteRepository;
  @Autowired private DraftQuoteLineRepository lineRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private QuoteValidationIssueRepository issueRepository;
  @Autowired private QuoteHandoffSnapshotRepository snapshotRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private OutboxEventRepository outboxEventRepository;
  @Autowired private ChangeRequestRepository changeRequestRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void approvedSubstituteCreatesSnapshotWithSelectedSubstituteAndOriginalReference() {
    Scenario s = approvedScenario(true);

    QuoteHandoffSnapshot snapshot = service.prepare(s.quoteId(), new QuoteHandoffCommand(UUID.randomUUID(), "OPERATOR", "Prepare"));

    assertThat(snapshot.getPayloadHash()).isNotBlank();
    assertThat(snapshot.getIdempotencyKey()).startsWith("quote-handoff:");
    assertThat(snapshot.getPayloadJson()).contains("selectedSubstituteProductId", s.substituteId().toString(), s.originalId().toString());
    assertThat(snapshot.getStatus()).isEqualTo("HANDOFF_PREPARED");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("QUOTE_HANDOFF_PREPARED");
    assertThat(outboxEventRepository.findAll()).extracting("eventType").contains("QUOTE_HANDOFF_PREPARED_INTERNAL");
  }

  @Test
  void repeatedPrepareCreatesVersionedImmutableSnapshots() {
    Scenario s = approvedScenario(false);

    QuoteHandoffSnapshot first = service.prepare(s.quoteId(), new QuoteHandoffCommand(UUID.randomUUID(), "OPERATOR", "first"));
    QuoteHandoffSnapshot second = service.prepare(s.quoteId(), new QuoteHandoffCommand(UUID.randomUUID(), "OPERATOR", "second"));

    assertThat(second.getPayloadVersion()).isGreaterThan(first.getPayloadVersion());
    assertThat(snapshotRepository.findAll()).hasSize(2);
  }

  @Test
  void nonApprovedQuoteCannotPrepareSnapshot() {
    Scenario s = scenario("READY_FOR_APPROVAL", false);

    assertThatThrownBy(() -> service.prepare(s.quoteId(), new QuoteHandoffCommand(UUID.randomUUID(), "OPERATOR", "Prepare")))
        .isInstanceOf(QuoteHandoffViolation.class)
        .hasMessageContaining("internally APPROVED");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("QUOTE_HANDOFF_BLOCKED");
  }

  @Test
  void changeRequestDraftCanBeCreatedFromApprovedQuoteWithoutConnectorCommand() {
    Scenario s = approvedScenario(true);

    var response = preparationService.createChangeRequestDraft(s.quoteId(), new ChangeRequestDraftCommand(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE"));

    assertThat(response.changeRequestId()).isNotNull();
    assertThat(response.hasSnapshot()).isTrue();
    // Stage 11E is external-execution-disabled by design; the response exposes a safe business flag
    // only, never the lower-layer connector execution status string.
    assertThat(response.externalExecutionEnabled()).isFalse();
    assertThat(changeRequestRepository.findAll()).hasSize(1);
    assertThat(changeRequestRepository.findAll().get(0).getApprovalStatus()).isEqualTo("DRAFT");
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("CHANGE_REQUEST_DRAFT_CREATED");
  }

  @Test
  void changeRequestCannotBeCreatedFromAnotherTenantQuote() {
    Scenario s = approvedScenario(false);
    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> preparationService.createChangeRequestDraft(s.quoteId(), new ChangeRequestDraftCommand(UUID.randomUUID(), "DEMO_ERP", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE")))
        .isInstanceOf(com.orderpilot.common.errors.NotFoundException.class);
    assertThat(connectorCommandRepository.count()).isZero();
  }

  private Scenario approvedScenario(boolean substitute) {
    return scenario("APPROVED", substitute);
  }

  private Scenario scenario(String status, boolean substitute) {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Product original = productRepository.save(new Product(tenantId, "TOY-CAM-2018-BPAD-OE", "Original brake pads", null, "Brake", null, null, "EA", "ACTIVE", null, "USD", NOW));
    Product sub = productRepository.save(new Product(tenantId, "AFT-CAM-2018-BPAD-A", "Substitute A", null, "Brake", null, null, "EA", "ACTIVE", null, "USD", NOW));
    DraftQuote quote = quoteRepository.save(new DraftQuote(tenantId, "DQ-11E", "API", null, null, UUID.randomUUID(), "Acme", status, "VALIDATED", false, "USD", UUID.randomUUID(), UUID.randomUUID(), NOW));
    DraftQuoteLine line = new DraftQuoteLine(tenantId, quote.getId(), null, original.getId(), substitute ? sub.getId() : null, 1, "Brake pads", BigDecimal.TEN, "EA", new BigDecimal("100.00"), BigDecimal.ZERO, null, "DRAFT", "VALIDATED", NOW);
    lineRepository.save(line);
    return new Scenario(quote.getId(), original.getId(), sub.getId());
  }

  private record Scenario(UUID quoteId, UUID originalId, UUID substituteId) {}

  @TestConfiguration
  static class JacksonConfig {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
  }
}
