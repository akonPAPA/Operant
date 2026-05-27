package com.orderpilot.application.services.integration;

import static org.assertj.core.api.Assertions.*;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.integration.*;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ChangeRequestService.class, AuditEventService.class, CoreConfiguration.class})
class ChangeRequestServiceStage9Test {
  private static final Instant NOW = Instant.parse("2026-05-27T00:00:00Z");

  @Autowired private ChangeRequestService service;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private DraftOrderRepository draftOrders;
  @Autowired private ExceptionCaseRepository exceptionCases;
  @Autowired private ChangeRequestRepository changeRequests;
  @Autowired private ConnectorSyncEventRepository syncEvents;
  @Autowired private ConnectorCommandRepository connectorCommands;
  @Autowired private InventorySnapshotRepository inventorySnapshots;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void approvedValidationBackedDraftQuoteCreatesAndExecutesDemoChangeRequest() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftQuote quote = approvedQuote(tenantId, validationBackedCase(tenantId).getId());
    long inventoryBefore = inventorySnapshots.count();
    long connectorCommandsBefore = connectorCommands.count();

    ChangeRequest request = service.createStage9DemoChangeRequest("DRAFT_QUOTE", quote.getId(), "CREATE_DRAFT_QUOTE", "{\"demo\":true}", UUID.randomUUID());
    ChangeRequest approved = service.approveChangeRequest(request.getId(), UUID.randomUUID());
    ChangeRequest executed = service.executeStage9DemoChangeRequest(approved.getId());

    assertThat(executed.getApprovalStatus()).isEqualTo("APPROVED");
    assertThat(executed.getExecutionStatus()).isEqualTo("EXECUTED");
    assertThat(executed.getExternalReference()).startsWith("DEMO-QUOTE-");
    assertThat(syncEvents.findByTenantIdOrderByStartedAtDesc(tenantId)).extracting("status").contains("SUCCESS");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("CHANGE_REQUEST_CREATED", "CHANGE_REQUEST_APPROVED", "DEMO_ERP_COMMAND_EXECUTED");
    assertThat(inventorySnapshots.count()).isEqualTo(inventoryBefore);
    assertThat(connectorCommands.count()).isEqualTo(connectorCommandsBefore);
  }

  @Test
  void botOnlyHandoffCannotCreateChangeRequest() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExceptionCase botCase = exceptionCases.save(new ExceptionCase(tenantId, "BOT-1", "BOT_CONVERSATION", UUID.randomUUID(), null, null, null, "Bot handoff", "OPEN", "NORMAL", "INFO", "bot-only", NOW));
    DraftQuote quote = approvedQuote(tenantId, botCase.getId());

    assertThatThrownBy(() -> service.createStage9DemoChangeRequest("DRAFT_QUOTE", quote.getId(), "CREATE_DRAFT_QUOTE", "{}", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Bot-only");
    assertThat(changeRequests.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void nonValidationBackedCaseCannotCreateChangeRequest() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ExceptionCase reviewCase = exceptionCases.save(new ExceptionCase(tenantId, "CASE-1", "MANUAL", UUID.randomUUID(), null, null, null, "Manual", "OPEN", "NORMAL", "INFO", "manual", NOW));
    DraftQuote quote = approvedQuote(tenantId, reviewCase.getId());

    assertThatThrownBy(() -> service.createStage9DemoChangeRequest("DRAFT_QUOTE", quote.getId(), "CREATE_DRAFT_QUOTE", "{}", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-validation-backed");
  }

  @Test
  void unapprovedDraftAndRejectedChangeRequestCannotExecute() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftQuote quote = new DraftQuote(tenantId, "Q-DRAFT", null, UUID.randomUUID(), UUID.randomUUID(), validationBackedCase(tenantId).getId(), "DRAFT", "USD", null, NOW);
    draftQuotes.save(quote);
    assertThatThrownBy(() -> service.createStage9DemoChangeRequest("DRAFT_QUOTE", quote.getId(), "CREATE_DRAFT_QUOTE", "{}", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approved");

    DraftQuote approvedQuote = approvedQuote(tenantId, validationBackedCase(tenantId).getId());
    ChangeRequest request = service.createStage9DemoChangeRequest("DRAFT_QUOTE", approvedQuote.getId(), "CREATE_DRAFT_QUOTE", "{}", null);
    ChangeRequest rejected = service.rejectChangeRequest(request.getId(), "no");
    assertThatThrownBy(() -> service.executeStage9DemoChangeRequest(rejected.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APPROVED");
  }

  @Test
  void demoAdapterFailureMarksRequestAndSyncFailed() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    DraftOrder order = approvedOrder(tenantId, validationBackedCase(tenantId).getId());
    ChangeRequest request = service.createStage9DemoChangeRequest("DRAFT_ORDER", order.getId(), "CREATE_DRAFT_ORDER", "{\"simulateFailure\":true}", null);
    service.approveChangeRequest(request.getId(), null);

    ChangeRequest failed = service.executeStage9DemoChangeRequest(request.getId());

    assertThat(failed.getExecutionStatus()).isEqualTo("FAILED");
    assertThat(failed.getExternalReference()).isNull();
    assertThat(syncEvents.findByTenantIdOrderByStartedAtDesc(tenantId)).extracting("status").contains("FAILED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("DEMO_ERP_COMMAND_FAILED");
  }

  @Test
  void analyticsAreTenantScopedForStage9ChangeRequests() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChangeRequest requestA = service.createStage9DemoChangeRequest("DRAFT_QUOTE", approvedQuote(tenantA, validationBackedCase(tenantA).getId()).getId(), "CREATE_DRAFT_QUOTE", "{}", null);
    TenantContext.setTenantId(tenantB);
    ChangeRequest requestB = service.createStage9DemoChangeRequest("DRAFT_QUOTE", approvedQuote(tenantB, validationBackedCase(tenantB).getId()).getId(), "CREATE_DRAFT_QUOTE", "{}", null);

    assertThat(service.listChangeRequests()).extracting("id").containsExactly(requestB.getId());
    assertThat(changeRequests.findByIdAndTenantId(requestA.getId(), tenantB)).isEmpty();
  }

  private ExceptionCase validationBackedCase(UUID tenantId) {
    UUID validationRunId = UUID.randomUUID();
    UUID extractionResultId = UUID.randomUUID();
    return exceptionCases.save(new ExceptionCase(tenantId, "VAL-" + UUID.randomUUID(), "VALIDATION_RUN", validationRunId, extractionResultId, validationRunId, null, "Validation review", "RESOLVED", "HIGH", "ERROR", "validation-backed", NOW));
  }

  private DraftQuote approvedQuote(UUID tenantId, UUID caseId) {
    DraftQuote quote = new DraftQuote(tenantId, "Q-" + UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), caseId, "DRAFT", "USD", null, NOW);
    quote.setStatus("APPROVED_INTERNAL", null, NOW);
    return draftQuotes.save(quote);
  }

  private DraftOrder approvedOrder(UUID tenantId, UUID caseId) {
    DraftOrder order = new DraftOrder(tenantId, "O-" + UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), caseId, "DRAFT", "USD", null, NOW);
    order.setStatus("APPROVED_INTERNAL", null, NOW);
    return draftOrders.save(order);
  }
}
