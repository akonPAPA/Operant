package com.orderpilot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.IntakeValidationService;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.analytics.CommerceAnalyticsService;
import com.orderpilot.application.services.channel.*;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.integration.CompensationPlanningService;
import com.orderpilot.application.services.integration.ConnectorIdempotencyService;
import com.orderpilot.application.services.integration.sandbox.*;
import com.orderpilot.application.services.reconciliation.InventoryReconciliationService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.common.tenant.TenantContextMissingException;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.channel.ChannelIdentityRepository;
import com.orderpilot.domain.intake.ChannelMessageRepository;
import com.orderpilot.domain.intake.ProcessingJobRepository;
import com.orderpilot.domain.integration.*;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.reconciliation.*;
import com.orderpilot.domain.workspace.DraftOrderRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.policy.TenantPolicyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
@Import({
    ChangeRequestService.class,
    ConnectorIdempotencyService.class,
    CompensationPlanningService.class,
    ConnectorSandboxExecutionService.class,
    SandboxConnectorAdapterRegistry.class,
    DemoErpSandboxConnectorAdapter.class,
    TenantPolicyService.class,
    ChannelIdentityService.class,
    ChannelGatewayService.class,
    IntakeValidationService.class,
    ProcessingJobService.class,
    CommerceAnalyticsService.class,
    InventoryReconciliationService.class,
    AuditEventService.class,
    CoreConfiguration.class,
    // OP-CAP-16F: reconciliation refresh now depends on the runtime guard chain.
    com.orderpilot.application.services.runtime.RuntimeGuardService.class,
    com.orderpilot.application.services.runtime.QuotaGuard.class,
    com.orderpilot.application.services.runtime.RateLimitService.class,
    com.orderpilot.application.services.runtime.FeatureEntitlementGuard.class,
    com.orderpilot.application.services.runtime.UsageMeterService.class
})
class TenantIsolationBoundaryTest {
  @Autowired private ChangeRequestService changeRequestService;
  @Autowired private ConnectorIdempotencyService connectorIdempotencyService;
  @Autowired private CompensationPlanningService compensationPlanningService;
  @Autowired private ConnectorSandboxExecutionService sandboxExecutionService;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  @Autowired private CompensationPlanRepository compensationPlanRepository;
  @Autowired private ChannelIdentityService channelIdentityService;
  @Autowired private ChannelGatewayService channelGatewayService;
  @Autowired private ChannelIdentityRepository channelIdentityRepository;
  @Autowired private ChannelMessageRepository channelMessageRepository;
  @Autowired private ProcessingJobRepository processingJobRepository;
  @Autowired private CommerceAnalyticsService analyticsService;
  @Autowired private InventoryReconciliationService reconciliationService;
  @Autowired private ReconciliationCaseRepository reconciliationCaseRepository;
  @Autowired private InventoryMovementRepository movementRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private DraftQuoteRepository draftQuoteRepository;
  @Autowired private DraftOrderRepository draftOrderRepository;
  @Autowired private InventorySnapshotRepository inventorySnapshotRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void tenantACannotAccessOrMutateTenantBConnectorCommandOrChangeRequest() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    ChangeRequest requestB = approvedChangeRequest("DEMO_ERP", "tenant-b-cr");
    ConnectorCommand commandB = connectorIdempotencyService.createCommandFromApprovedChangeRequest(requestB.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");

    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> changeRequestService.getChangeRequest(requestB.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> changeRequestService.approveChangeRequest(requestB.getId(), UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> changeRequestService.markExecutionDisabled(requestB.getId(), "cross tenant"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> connectorIdempotencyService.get(commandB.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> connectorIdempotencyService.createCommandFromApprovedChangeRequest(requestB.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}"))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> sandboxExecutionService.executeDryRun(commandB.getId(), "tenant-a-cannot-run-b", null))
        .isInstanceOf(NotFoundException.class);

    assertThat(connectorCommandRepository.findById(commandB.getId()).orElseThrow().getStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
  }

  @Test
  void tenantACannotAccessTenantBSandboxExecutionOrReuseItsDryRunKey() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    ConnectorCommand commandB = connectorIdempotencyService.createCommandFromApprovedChangeRequest(approvedChangeRequest("DEMO_ERP", "tenant-b-sandbox").getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");
    var executionB = sandboxExecutionService.executeDryRun(commandB.getId(), "shared-dry-run-key", null).execution();

    TenantContext.setTenantId(tenantA);
    ConnectorCommand commandA = connectorIdempotencyService.createCommandFromApprovedChangeRequest(approvedChangeRequest("DEMO_ERP", "tenant-a-sandbox").getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");
    var executionA = sandboxExecutionService.executeDryRun(commandA.getId(), "shared-dry-run-key", null).execution();

    assertThat(executionA.getId()).isNotEqualTo(executionB.getId());
    assertThatThrownBy(() -> sandboxExecutionService.getExecution(executionB.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThat(sandboxExecutionRepository.count()).isEqualTo(2);
  }

  @Test
  void tenantACannotAccessTenantBCompensationPlanOrCreatePlanFromTenantBResources() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    ChangeRequest requestB = approvedChangeRequest("DEMO_ERP", "tenant-b-comp");
    ConnectorCommand commandB = connectorIdempotencyService.createCommandFromApprovedChangeRequest(requestB.getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");
    CompensationPlan planB = compensationPlanningService.planForConnectorCommand(commandB.getId(), ConnectorCommandExecutionState.EXECUTION_BLOCKED, CompensationReasonCode.EXTERNAL_WRITE_NOT_EXECUTED, "blocked", null);

    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> compensationPlanningService.getPlan(planB.getId()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> compensationPlanningService.planForConnectorCommand(commandB.getId(), ConnectorCommandExecutionState.EXECUTION_BLOCKED, CompensationReasonCode.EXTERNAL_WRITE_NOT_EXECUTED, "blocked", null))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> compensationPlanningService.planForTenantPolicyBlock(requestB.getId(), null))
        .isInstanceOf(NotFoundException.class);
    assertThat(compensationPlanRepository.findById(planB.getId()).orElseThrow().isSafeToAutoExecute()).isFalse();
  }

  @Test
  void tenantACannotResolveLinkOrReadTenantBChannelIdentityOrMessage() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    var identityB = channelIdentityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "shared-sender", "chat-b", null, "Tenant B Sender");
    var messageB = channelGatewayService.accept(new NormalizedInboundMessage(null, ChannelType.TELEGRAM, "msg-b", "chat-b", "shared-sender", "Tenant B Sender", null, "Need quote", List.of(), Instant.parse("2026-05-20T00:00:00Z"), "{}", "msg-b"));

    TenantContext.setTenantId(tenantA);
    var identityA = channelIdentityService.findOrCreateUnlinkedIdentity(ChannelType.TELEGRAM, "shared-sender", "chat-a", null, "Tenant A Sender");

    assertThat(identityA.getId()).isNotEqualTo(identityB.getId());
    assertThatThrownBy(() -> channelIdentityService.getIdentity(identityB.getId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Channel identity not found");
    assertThatThrownBy(() -> channelIdentityService.linkIdentity(identityB.getId(), UUID.randomUUID(), null, UUID.randomUUID(), "cross tenant"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantA)).isEmpty();
    assertThat(channelMessageRepository.findByTenantIdOrderByReceivedAtDesc(tenantB)).extracting("id").contains(messageB.getId());
  }

  @Test
  void channelGatewayRejectsSpoofedTenantAndDoesNotCreateSideEffects() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> channelGatewayService.accept(new NormalizedInboundMessage(tenantB, ChannelType.API, "spoofed-msg", "spoofed-conv", "spoofed-sender", null, null, "Need quote", List.of(), null, "{\"tenantId\":\"" + tenantB + "\"}", "spoofed-key")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalized tenant_id must match current tenant context");

    assertThat(channelIdentityRepository.count()).isZero();
    assertThat(channelMessageRepository.count()).isZero();
    assertThat(processingJobRepository.count()).isZero();
  }

  @Test
  void analyticsAndReconciliationUseTenantScopedCountsAndLookups() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    reconciliationCaseRepository.save(new ReconciliationCase(tenantB, productId, locationId, new BigDecimal("10"), new BigDecimal("0"), new BigDecimal("-10"), ReconciliationSeverity.HIGH, "[\"tenant b only\"]", Instant.parse("2026-05-20T00:00:00Z")));

    TenantContext.setTenantId(tenantA);
    var summaryA = analyticsService.summary();
    var casesA = reconciliationService.listCases(0, 50);

    assertThat(summaryA.openReconciliationCases()).isZero();
    assertThat(summaryA.highSeverityReconciliationCases()).isZero();
    assertThat(casesA.totalElements()).isZero();
    UUID tenantBCaseId = reconciliationCaseRepository.findByTenantIdOrderByUpdatedAtDesc(tenantB, org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0).getId();
    assertThatThrownBy(() -> reconciliationService.getCase(tenantBCaseId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> reconciliationService.updateCaseStatus(tenantBCaseId, ReconciliationStatus.RESOLVED.name()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void auditQueriesAreTenantScopedAndMissingTenantContextDenies() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "audit-a", null);
    TenantContext.setTenantId(tenantB);
    changeRequestService.createChangeRequest("DEMO_ERP", "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", "audit-b", null);

    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantA)).allMatch(event -> event.getTenantId().equals(tenantA));
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(tenantB)).allMatch(event -> event.getTenantId().equals(tenantB));

    TenantContext.clear();
    assertThatThrownBy(() -> changeRequestService.listChangeRequests())
        .isInstanceOf(TenantContextMissingException.class);
  }

  @Test
  void deniedBoundariesDoNotCreateBusinessOrExternalExecutionSideEffects() {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    TenantContext.setTenantId(tenantB);
    ConnectorCommand commandB = connectorIdempotencyService.createCommandFromApprovedChangeRequest(approvedChangeRequest("DEMO_ERP", "side-effects-b").getId(), null, "DEMO_ERP", "CREATE_DRAFT_QUOTE", "{}");

    long sandboxBefore = sandboxExecutionRepository.count();
    long compensationBefore = compensationPlanRepository.count();
    long quoteBefore = draftQuoteRepository.count();
    long orderBefore = draftOrderRepository.count();
    long inventoryBefore = inventorySnapshotRepository.count();
    long priceBefore = priceRuleRepository.count();
    TenantContext.setTenantId(tenantA);

    assertThatThrownBy(() -> sandboxExecutionService.executeDryRun(commandB.getId(), "denied-side-effects", null))
        .isInstanceOf(NotFoundException.class);

    assertThat(sandboxExecutionRepository.count()).isEqualTo(sandboxBefore);
    assertThat(compensationPlanRepository.count()).isEqualTo(compensationBefore);
    assertThat(draftQuoteRepository.count()).isEqualTo(quoteBefore);
    assertThat(draftOrderRepository.count()).isEqualTo(orderBefore);
    assertThat(inventorySnapshotRepository.count()).isEqualTo(inventoryBefore);
    assertThat(priceRuleRepository.count()).isEqualTo(priceBefore);
    assertThat(connectorCommandRepository.findById(commandB.getId()).orElseThrow().getStatus()).isEqualTo("EXECUTION_DISABLED");
  }

  private ChangeRequest approvedChangeRequest(String targetSystem, String key) {
    ChangeRequest request = changeRequestService.createChangeRequest(targetSystem, "DRAFT_QUOTE", "EXPORT", "DRAFT_QUOTE", UUID.randomUUID(), "{}", key, null);
    changeRequestService.validateChangeRequest(request.getId());
    return changeRequestService.approveChangeRequest(request.getId(), UUID.randomUUID());
  }
}
