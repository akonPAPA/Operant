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
@Import({ChangeRequestService.class, ConnectorExecutionSafetyService.class, AuditEventService.class, CoreConfiguration.class})
class ConnectorExecutionSafetyStage9Test {
  private static final Instant NOW = Instant.parse("2026-05-27T00:00:00Z");

  @Autowired private ChangeRequestService changeRequestService;
  @Autowired private ConnectorExecutionSafetyService safetyService;
  @Autowired private DraftQuoteRepository draftQuotes;
  @Autowired private ExceptionCaseRepository exceptionCases;
  @Autowired private ConnectorSyncEventRepository syncEvents;
  @Autowired private ConnectorCommandRepository connectorCommands;
  @Autowired private InventorySnapshotRepository inventorySnapshots;
  @Autowired private AuditEventRepository auditEvents;

  @AfterEach void clearTenant() { TenantContext.clear(); }

  @Test
  void duplicateExecuteReturnsSameExternalReferenceAndAuditsReplay() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    long commandsBefore = connectorCommands.count();
    ChangeRequest request = approvedChangeRequest(tenantId, "{}");

    ChangeRequest first = changeRequestService.executeStage9DemoChangeRequest(request.getId());
    ChangeRequest second = changeRequestService.executeStage9DemoChangeRequest(request.getId());

    assertThat(second.getExternalReference()).isEqualTo(first.getExternalReference());
    assertThat(second.getConnectorIdempotencyKey()).isEqualTo(first.getConnectorIdempotencyKey());
    assertThat(second.getConnectorIdempotencyKey()).isEqualTo(safetyService.connectorIdempotencyKeyHash(request));
    assertThat(second.getConnectorIdempotencyKey()).startsWith("sha256:");
    assertThat(second.getConnectorIdempotencyKey()).doesNotContain("demo-erp:", tenantId.toString(), request.getId().toString());
    assertThat(second.getConnectorAttemptCount()).isEqualTo(1);
    assertThat(syncEvents.findByTenantIdOrderByStartedAtDesc(tenantId)).hasSize(1);
    assertThat(connectorCommands.count()).isEqualTo(commandsBefore);
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .filteredOn(event -> "DEMO_ERP_IDEMPOTENT_REPLAY".equals(event.getAction()))
        .singleElement()
        .satisfies(event -> assertThat(event.getMetadata())
            .contains(first.getExternalReference(), "\"idempotencyKeyHash\":\"" + first.getConnectorIdempotencyKey() + "\"", "\"replay\":true", "\"networkCall\":false")
            .doesNotContain("demo-erp:" + tenantId));
  }

  @Test
  void retryRequiresRetryableFailureAndDoesNotMutateInventory() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    long inventoryBefore = inventorySnapshots.count();
    ChangeRequest request = approvedChangeRequest(tenantId, "{\"simulateFailure\":true}");

    ChangeRequest failed = changeRequestService.executeStage9DemoChangeRequest(request.getId());
    assertThat(failed.getExecutionStatus()).isEqualTo("FAILED");
    assertThat(failed.isConnectorRetryable()).isTrue();
    assertThat(failed.getConnectorAttemptCount()).isEqualTo(1);
    assertThat(failed.getConnectorLastAttemptAt()).isNotNull();
    assertThat(failed.getConnectorNextRetryAt()).isNotNull();
    assertThat(failed.getConnectorFailureType()).isEqualTo(ConnectorFailureType.TRANSIENT_ERROR);

    ChangeRequest retried = changeRequestService.retryStage9DemoChangeRequest(request.getId());

    assertThat(retried.getConnectorAttemptCount()).isEqualTo(2);
    assertThat(retried.getExecutionStatus()).isEqualTo("FAILED");
    assertThat(inventorySnapshots.count()).isEqualTo(inventoryBefore);
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("DEMO_ERP_MANUAL_RETRY_REQUESTED");
  }

  @Test
  void terminalFailureDoesNotRetryForever() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChangeRequest request = approvedChangeRequest(tenantId, "{\"simulateFailure\":true}");

    changeRequestService.executeStage9DemoChangeRequest(request.getId());
    changeRequestService.retryStage9DemoChangeRequest(request.getId());
    ChangeRequest terminal = changeRequestService.retryStage9DemoChangeRequest(request.getId());

    assertThat(terminal.getConnectorAttemptCount()).isEqualTo(terminal.getConnectorMaxAttempts());
    assertThat(terminal.isConnectorRetryable()).isFalse();
    assertThat(terminal.getConnectorNextRetryAt()).isNull();
    assertThatThrownBy(() -> changeRequestService.retryStage9DemoChangeRequest(request.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not retryable");
  }

  @Test
  void nonDemoTargetIsBlockedByPolicyAndAudited() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChangeRequest request = changeRequestService.createChangeRequest("ONE_C", "DRAFT_QUOTE", "CREATE_DRAFT_QUOTE", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "stage9b-non-demo", null);
    changeRequestService.approveChangeRequest(request.getId(), UUID.randomUUID());

    assertThatThrownBy(() -> changeRequestService.executeStage9DemoChangeRequest(request.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("demo ERP adapter");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .extracting("action")
        .contains("CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED");
    assertThat(connectorCommands.count()).isZero();
  }

  @Test
  void nonRetryableFailureCannotBeRetried() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChangeRequest request = approvedChangeRequest(tenantId, "{\"simulatePermanentFailure\":true}");
    changeRequestService.executeStage9DemoChangeRequest(request.getId());

    assertThatThrownBy(() -> changeRequestService.retryStage9DemoChangeRequest(request.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not retryable");
    assertThat(safetyService.safety(request.getId()).retryAllowed()).isFalse();
  }

  @Test
  void rejectedAndCancelledChangeRequestsCannotRun() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    ChangeRequest rejected = approvedChangeRequest(tenantId, "{}");
    changeRequestService.rejectChangeRequest(rejected.getId(), "no");
    assertThatThrownBy(() -> changeRequestService.retryStage9DemoChangeRequest(rejected.getId())).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> changeRequestService.executeStage9DemoChangeRequest(rejected.getId())).isInstanceOf(IllegalStateException.class);

    ChangeRequest cancelled = approvedChangeRequest(tenantId, "{}");
    changeRequestService.cancelStage9DemoChangeRequest(cancelled.getId(), "stop");
    assertThatThrownBy(() -> changeRequestService.executeStage9DemoChangeRequest(cancelled.getId())).isInstanceOf(IllegalStateException.class);
  }

  // OP-CAP-42A: approval-bypass negative proof. A freshly created (never-approved) ChangeRequest is
  // PENDING_APPROVAL and must fail closed on execute — no connector command, no sync event, no
  // external reference — and the block must be audited. Approval is the human gate before any
  // external execution; skipping it would violate "Human approves if risky / external execution only
  // after explicit gated approval".
  @Test
  void pendingApprovalChangeRequestCannotBeExecutedAndIsAudited() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    long commandsBefore = connectorCommands.count();
    ChangeRequest pending =
        changeRequestService.createStage9DemoChangeRequest(
            "DRAFT_QUOTE",
            approvedQuote(tenantId, validationBackedCase(tenantId).getId()).getId(),
            "CREATE_DRAFT_QUOTE",
            "{}",
            null);
    assertThat(pending.getApprovalStatus()).isEqualTo("PENDING_APPROVAL");

    assertThatThrownBy(() -> changeRequestService.executeStage9DemoChangeRequest(pending.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APPROVED");

    ChangeRequest reloaded = changeRequestService.getChangeRequest(pending.getId());
    assertThat(reloaded.getExecutionStatus()).isNotEqualTo("EXECUTED");
    assertThat(reloaded.getExternalReference()).isNull();
    assertThat(connectorCommands.count()).isEqualTo(commandsBefore);
    assertThat(syncEvents.findByTenantIdOrderByStartedAtDesc(tenantId)).isEmpty();
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId))
        .filteredOn(event -> "CHANGE_REQUEST_EXECUTION_POLICY_BLOCKED".equals(event.getAction()))
        .singleElement()
        .satisfies(event -> assertThat(event.getMetadata())
            .contains("\"reasonCode\":\"APPROVAL_REQUIRED\"", "\"networkCall\":false"));
  }

  @Test
  void tenantCannotExecuteAnotherTenantChangeRequest() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChangeRequest requestA = approvedChangeRequest(tenantA, "{}");

    TenantContext.setTenantId(tenantB);
    assertThatThrownBy(() -> changeRequestService.executeStage9DemoChangeRequest(requestA.getId()))
        .hasMessageContaining("not found");
    assertThat(syncEvents.findByTenantIdOrderByStartedAtDesc(tenantB)).isEmpty();
  }

  @Test
  void credentialPlaceholderAndConnectorAuditAreTenantScoped() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    ChangeRequest requestA = approvedChangeRequest(tenantA, "{}");
    changeRequestService.executeStage9DemoChangeRequest(requestA.getId());
    assertThat(safetyService.policies().executionMode()).isEqualTo(ConnectorExecutionMode.DEMO_ONLY.name());
    assertThat(safetyService.audit().events()).isNotEmpty();

    TenantContext.setTenantId(tenantB);
    assertThat(safetyService.audit().events()).isEmpty();
  }

  private ChangeRequest approvedChangeRequest(UUID tenantId, String payload) {
    ChangeRequest request = changeRequestService.createStage9DemoChangeRequest("DRAFT_QUOTE", approvedQuote(tenantId, validationBackedCase(tenantId).getId()).getId(), "CREATE_DRAFT_QUOTE", payload, null);
    return changeRequestService.approveChangeRequest(request.getId(), UUID.randomUUID());
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
}
