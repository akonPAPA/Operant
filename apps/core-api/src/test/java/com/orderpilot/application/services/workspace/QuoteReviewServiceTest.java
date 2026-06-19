package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage12ADtos.*;
import com.orderpilot.api.dto.Stage12CDtos.*;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.integration.ChangeRequestService;
import com.orderpilot.application.services.ProductCatalogMatchingService;
import com.orderpilot.application.services.ProductCodeNormalizer;
import com.orderpilot.application.services.ProductSubstitutionService;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.MarginRule;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.*;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    QuoteReviewService.class,
    QuoteDraftService.class,
    CustomerResolutionService.class,
    ProductResolutionService.class,
    QuoteInventoryValidationService.class,
    PricingService.class,
    QuoteMarginValidationService.class,
    SubstitutionService.class,
    ApprovalPolicyService.class,
    QuoteLifecycleService.class,
    ProductCatalogMatchingService.class,
    ProductSubstitutionService.class,
    AuditEventService.class,
    ChangeRequestService.class,
    CoreConfiguration.class
})
class QuoteReviewServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private QuoteReviewService service;
  @Autowired private QuoteDraftService quoteDraftService;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private ProductAliasRepository aliases;
  @Autowired private ProductSubstituteRepository substitutes;
  @Autowired private ProductCompatibilityRepository compatibility;
  @Autowired private CustomerSubstitutionPreferenceRepository substitutionPreferences;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private LocationRepository locations;
  @Autowired private PriceRuleRepository prices;
  @Autowired private MarginRuleRepository margins;
  @Autowired private QuoteValidationIssueRepository issues;
  @Autowired private QuoteApprovalRequestRepository approvals;
  @Autowired private DraftQuoteRepository quotes;
  @Autowired private AuditEventRepository auditEvents;
  @Autowired private com.orderpilot.domain.integration.ChangeRequestRepository changeRequests;
  @Autowired private com.orderpilot.domain.integration.OutboxEventRepository outboxEvents;
  @Autowired private com.orderpilot.domain.integration.ConnectorSyncEventRepository connectorSyncEvents;

  @MockBean private ChannelToQuoteWiringService channelToQuoteWiringService;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void reviewQueueReturnsOnlyTenantOwnedQuotes() {
    Scenario tenantB = unresolvedQuoteScenario();
    UUID quoteB = tenantB.quote().draftQuoteId();
    UUID tenantA = tenant();
    customer("A-CUST", "A", "Tenant A");
    QuoteTransactionResponse quoteA = quoteDraftService.createFromRfq(command(tenantA, "A-CUST", "UNKNOWN-A", "1", "0.00", "queue-a"));

    assertThat(service.queue(null, null, null, null, null, null, null, null, null, null))
        .extracting(QuoteReviewQueueRow::quoteId)
        .contains(quoteA.draftQuoteId())
        .doesNotContain(quoteB);
  }

  @Test
  void reviewDetailIncludesIssuesSubstitutesApprovalsAndAuditTimeline() {
    Scenario s = substituteScenario(false, true);
    service.escalateIssue(s.quote().draftQuoteId(), s.quote().validationIssues().get(0).id(), new EscalateValidationIssueCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "MANAGER_REVIEW", "Escalate"));

    QuoteReviewDetail detail = service.detail(s.quote().draftQuoteId());

    assertThat(detail.header().quoteId()).isEqualTo(s.quote().draftQuoteId());
    assertThat(detail.draftQuoteLines()).hasSize(1);
    assertThat(detail.validationIssues()).isNotEmpty();
    assertThat(detail.proposedSubstitutes()).extracting(SubstituteCandidate::productId).contains(s.substitute().getId());
    assertThat(detail.approvalRequirements()).extracting(ApprovalRequest::reasonCode).contains("MANAGER_REVIEW");
    assertThat(detail.auditTimeline()).extracting(AuditTimelineEvent::action).contains("VALIDATION_ISSUE_ESCALATED");
  }

  @Test
  void unresolvedCustomerCorrectionRevalidatesAndAuditsPreviousNewValues() {
    Scenario s = unresolvedQuoteScenario();
    CustomerAccount customer = customer("FIX-CUST", "FIX", "Fixed Customer");

    QuoteReviewCommandResult result = service.correctCustomer(s.quote().draftQuoteId(), new CorrectQuoteCustomerCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", customer.getId(), "CUSTOMER_SELECTED", "Matched account"));

    assertThat(result.validationIssues()).extracting(ValidationIssue::issueCode).contains("PRODUCT_NOT_RESOLVED");
    assertThat(result.validationIssues().stream().filter(issue -> "CUSTOMER_NOT_RESOLVED".equals(issue.issueCode())).findFirst().orElseThrow().status()).isEqualTo("RESOLVED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId()).stream()
        .filter(event -> "QUOTE_CUSTOMER_CORRECTED".equals(event.getAction()))
        .findFirst()
        .orElseThrow()
        .getMetadata()).contains("previousValue", "newValue", customer.getId().toString(), "validationSummary");
  }

  @Test
  void lineQuantityAndUomCorrectionRevalidatesQuote() {
    Scenario s = pricedQuoteWithInvalidLine();
    UUID lineId = s.quote().lines().get(0).id();

    QuoteReviewCommandResult result = service.correctLine(s.quote().draftQuoteId(), lineId, new CorrectQuoteLineCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", new BigDecimal("2"), "EA", null, false, false, "LINE_CORRECTED", "Fix qty and UOM"));

    assertThat(result.action()).isEqualTo("QUOTE_LINE_CORRECTED");
    assertThat(result.validationIssues().stream().filter(issue -> "INVALID_QUANTITY".equals(issue.issueCode())).findFirst().orElseThrow().status()).isEqualTo("RESOLVED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("QUOTE_REVALIDATED", "QUOTE_LINE_CORRECTED");
  }

  @Test
  void invalidSelectedLineCorrectionIsRejected() {
    Scenario s = unresolvedQuoteScenario();

    assertThatThrownBy(() -> service.correctLine(s.quote().draftQuoteId(), UUID.randomUUID(), new CorrectQuoteLineCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", BigDecimal.ONE, "EA", null, false, false, "BAD_LINE", "Bad line")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void resolvingValidationIssueEmitsAudit() {
    Scenario s = unresolvedQuoteScenario();

    QuoteReviewCommandResult result = service.resolveIssue(s.quote().draftQuoteId(), s.quote().validationIssues().get(0).id(), new ResolveValidationIssueCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "OPERATOR_CONFIRMED", "Handled"));

    assertThat(result.action()).isEqualTo("VALIDATION_ISSUE_RESOLVED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("VALIDATION_ISSUE_RESOLVED");
  }

  @Test
  void substituteSelectionAppliesOnlyValidCompatibleSubstitute() {
    Scenario s = substituteScenario(false, false);
    UUID lineId = s.quote().lines().get(0).id();

    QuoteReviewCommandResult result = service.selectSubstitute(s.quote().draftQuoteId(), lineId, new QuoteLineSubstituteCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", s.substitute().getId(), "SAFE_SUBSTITUTE", "Use available substitute"));

    assertThat(result.action()).isEqualTo("QUOTE_SUBSTITUTE_SELECTED");
    assertThat(result.validationIssues().stream().filter(issue -> "INSUFFICIENT_STOCK".equals(issue.issueCode())).findFirst().orElseThrow().status()).isEqualTo("RESOLVED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("QUOTE_SUBSTITUTE_SELECTED");
  }

  @Test
  void blockedSubstituteCannotBeSelectedAndHighRiskRoutesToApproval() {
    Scenario blocked = substituteScenario(true, false);
    UUID lineId = blocked.quote().lines().get(0).id();

    assertThatThrownBy(() -> service.selectSubstitute(blocked.quote().draftQuoteId(), lineId, new QuoteLineSubstituteCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", blocked.substitute().getId(), "BLOCKED", "Try blocked")))
        .isInstanceOf(QuoteLifecycleViolation.class);

    Scenario risky = substituteScenario(false, true);
    QuoteReviewCommandResult result = service.selectSubstitute(risky.quote().draftQuoteId(), risky.quote().lines().get(0).id(), new QuoteLineSubstituteCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", risky.substitute().getId(), "RISKY_SUBSTITUTE", "Needs manager"));

    assertThat(result.newStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(approvals.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), risky.quote().draftQuoteId())).extracting(QuoteApprovalRequest::getReasonCode).contains("SUBSTITUTE_REQUIRES_APPROVAL");
  }

  @Test
  void marginViolationCannotBeAutoApprovedByReviewCorrection() {
    Scenario s = marginRiskScenario();

    QuoteReviewCommandResult result = service.resolveIssue(s.quote().draftQuoteId(), s.quote().validationIssues().stream().filter(issue -> "MARGIN_BELOW_GUARDRAIL".equals(issue.issueCode())).findFirst().orElseThrow().id(), new ResolveValidationIssueCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "ACKNOWLEDGED", "Ack"));

    assertThat(result.newStatus()).isNotEqualTo("APPROVED");
    assertThat(result.approvalRequired()).isTrue();
  }

  @Test
  void crossTenantIssueAndSubstituteAccessBlocked() {
    Scenario s = substituteScenario(false, false);
    UUID quoteId = s.quote().draftQuoteId();
    UUID issueId = s.quote().validationIssues().get(0).id();
    UUID substituteId = s.substitute().getId();
    UUID lineId = s.quote().lines().get(0).id();

    TenantContext.setTenantId(UUID.randomUUID());

    assertThatThrownBy(() -> service.resolveIssue(quoteId, issueId, new ResolveValidationIssueCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "X", "X")))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.selectSubstitute(quoteId, lineId, new QuoteLineSubstituteCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", substituteId, "X", "X")))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void rejectedQuoteCannotBeCorrected() {
    Scenario s = unresolvedQuoteScenario();
    DraftQuote quote = quotes.findByIdAndTenantId(s.quote().draftQuoteId(), TenantContext.requireTenantId()).orElseThrow();
    quote.transition("REJECTED", "NEEDS_REVIEW", true, UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> service.correctLine(s.quote().draftQuoteId(), s.quote().lines().get(0).id(), new CorrectQuoteLineCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", BigDecimal.ONE, "EA", null, false, false, "FIX", "Fix")))
        .isInstanceOf(QuoteLifecycleViolation.class);
  }

  @Test
  void assembleDraftFromCleanReviewProducesAssembledDraftWithoutApproval() {
    Scenario s = cleanQuoteScenario();

    QuoteDraftSummary summary = service.assembleDraft(s.quote().draftQuoteId(), new AssembleQuoteDraftCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Validated by operator"));

    assertThat(summary.quoteId()).isEqualTo(s.quote().draftQuoteId());
    assertThat(summary.draftStatus()).isEqualTo("DRAFT_ASSEMBLED");
    assertThat(summary.approvalRequired()).isFalse();
    assertThat(summary.unresolvedBlockingIssueCount()).isZero();
    assertThat(summary.riskLevel()).isEqualTo("LOW");
    assertThat(summary.lineCount()).isEqualTo(1);
    assertThat(summary.externalExecution()).isEqualTo("DISABLED");
    assertThat(summary.nextAction()).isEqualTo("READY_FOR_INTERNAL_APPROVAL");
    assertThat(quotes.findByIdAndTenantId(s.quote().draftQuoteId(), TenantContext.requireTenantId()).orElseThrow().getStatus()).isEqualTo("DRAFT_ASSEMBLED");
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("QUOTE_DRAFT_ASSEMBLED");
  }

  @Test
  void assembleDraftIsBlockedWhileUnresolvedBlockingIssueRemains() {
    Scenario s = pricedQuoteWithInvalidLine();

    assertThatThrownBy(() -> service.assembleDraft(s.quote().draftQuoteId(), new AssembleQuoteDraftCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Try assemble with blocker")))
        .isInstanceOf(QuoteLifecycleViolation.class);
    assertThat(quotes.findByIdAndTenantId(s.quote().draftQuoteId(), TenantContext.requireTenantId()).orElseThrow().getStatus()).isNotEqualTo("DRAFT_ASSEMBLED");
  }

  @Test
  void assembleDraftAfterEscalationRequiresApprovalAndStaysPendingApproval() {
    Scenario s = marginRiskScenario();
    // Escalate the single blocking margin issue to the approval path. This clears
    // the open blocking issue (it becomes ESCALATED) and opens an approval request,
    // so the readiness gate passes but approval is still required.
    UUID issueId = s.quote().validationIssues().stream().filter(issue -> "MARGIN_BELOW_GUARDRAIL".equals(issue.issueCode())).findFirst().orElseThrow().id();
    service.escalateIssue(s.quote().draftQuoteId(), issueId, new EscalateValidationIssueCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "MANAGER_REVIEW", "Escalate"));

    QuoteDraftSummary summary = service.assembleDraft(s.quote().draftQuoteId(), new AssembleQuoteDraftCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Assemble after escalation"));

    assertThat(summary.approvalRequired()).isTrue();
    assertThat(summary.draftStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(summary.riskLevel()).isEqualTo("HIGH");
    assertThat(summary.nextAction()).isEqualTo("APPROVAL_DECISION_REQUIRED");
    assertThat(summary.unresolvedBlockingIssueCount()).isZero();
  }

  @Test
  void assembleDraftCannotAssembleTerminalQuote() {
    Scenario s = cleanQuoteScenario();
    DraftQuote quote = quotes.findByIdAndTenantId(s.quote().draftQuoteId(), TenantContext.requireTenantId()).orElseThrow();
    quote.transition("APPROVED", "VALIDATED", false, UUID.randomUUID(), NOW);
    quotes.save(quote);

    assertThatThrownBy(() -> service.assembleDraft(s.quote().draftQuoteId(), new AssembleQuoteDraftCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Assemble terminal")))
        .isInstanceOf(QuoteLifecycleViolation.class);
  }

  @Test
  void assembleDraftCrossTenantAccessBlocked() {
    Scenario s = cleanQuoteScenario();
    UUID quoteId = s.quote().draftQuoteId();
    UUID otherTenant = UUID.randomUUID();
    TenantContext.setTenantId(otherTenant);

    assertThatThrownBy(() -> service.assembleDraft(quoteId, new AssembleQuoteDraftCommand(TenantContext.requireTenantId(), UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Cross tenant")))
        .isInstanceOf(NotFoundException.class);
    // OP-CAP-37: a denied cross-tenant assemble creates no candidate and no external effect.
    assertThat(changeRequests.findByTenantIdOrderByCreatedAtDesc(otherTenant)).isEmpty();
    assertThat(outboxEvents.findByTenantIdOrderByCreatedAtDesc(otherTenant)).isEmpty();
  }

  // ---- OP-CAP-37: Draft-Assembled external-sync ChangeRequest candidate ----

  @Test
  void assembleDraftPreparesTenantScopedNonExecutedSyncCandidate() {
    Scenario s = cleanQuoteScenario();
    UUID tenantId = TenantContext.requireTenantId();
    UUID quoteId = s.quote().draftQuoteId();

    QuoteDraftSummary summary = service.assembleDraft(quoteId, new AssembleQuoteDraftCommand(tenantId, UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Assemble clean"));

    assertThat(summary.externalSyncCandidateStatus()).isEqualTo("PREPARED");
    assertThat(summary.externalExecution()).isEqualTo("DISABLED");

    var candidates = changeRequests.findByTenantIdOrderByCreatedAtDesc(tenantId);
    assertThat(candidates).hasSize(1);
    var candidate = candidates.get(0);
    assertThat(candidate.getTenantId()).isEqualTo(tenantId);
    assertThat(candidate.getSourceId()).isEqualTo(quoteId);
    assertThat(candidate.getSourceType()).isEqualTo("QUOTE_REVIEW");
    assertThat(candidate.getTargetSystem()).isEqualTo("INTERNAL_SYNC_CANDIDATE");
    assertThat(candidate.getRequestedAction()).isEqualTo("QUOTE_EXTERNAL_SYNC_CANDIDATE");
    assertThat(candidate.getExecutionStatus()).isEqualTo("EXECUTION_DISABLED");
    assertThat(candidate.getApprovalStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(candidate.getExecutedAt()).isNull();
    assertThat(candidate.getExternalReference()).isNull();
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("QUOTE_DRAFT_EXTERNAL_SYNC_CANDIDATE_CREATED");
    // External-execution negative proof: no connector sync event, no outbox event from this path.
    assertThat(connectorSyncEvents.findByTenantIdOrderByStartedAtDesc(tenantId)).isEmpty();
    assertThat(outboxEvents.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  @Test
  void repeatedAssembleDoesNotDuplicateActiveCandidate() {
    Scenario s = cleanQuoteScenario();
    UUID tenantId = TenantContext.requireTenantId();
    UUID quoteId = s.quote().draftQuoteId();

    service.assembleDraft(quoteId, new AssembleQuoteDraftCommand(tenantId, UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "first"));
    service.assembleDraft(quoteId, new AssembleQuoteDraftCommand(tenantId, UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "second"));

    assertThat(changeRequests.findByTenantIdOrderByCreatedAtDesc(tenantId)).hasSize(1);
    assertThat(auditEvents.findByTenantIdOrderByOccurredAtDesc(tenantId)).extracting("action").contains("QUOTE_DRAFT_EXTERNAL_SYNC_CANDIDATE_REUSED");
  }

  @Test
  void assembleRequiringApprovalDoesNotPrepareCandidate() {
    Scenario s = marginRiskScenario();
    UUID tenantId = TenantContext.requireTenantId();
    UUID issueId = s.quote().validationIssues().stream().filter(issue -> "MARGIN_BELOW_GUARDRAIL".equals(issue.issueCode())).findFirst().orElseThrow().id();
    service.escalateIssue(s.quote().draftQuoteId(), issueId, new EscalateValidationIssueCommand(tenantId, UUID.randomUUID(), "OPERATOR", "MANAGER_REVIEW", "Escalate"));

    QuoteDraftSummary summary = service.assembleDraft(s.quote().draftQuoteId(), new AssembleQuoteDraftCommand(tenantId, UUID.randomUUID(), "OPERATOR", "OPERATOR_REVIEW", "Assemble pending approval"));

    assertThat(summary.draftStatus()).isEqualTo("PENDING_APPROVAL");
    assertThat(summary.externalSyncCandidateStatus()).isEqualTo("PENDING_INTERNAL_APPROVAL");
    assertThat(changeRequests.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  private Scenario cleanQuoteScenario() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product product = product("BRK-001", "Brake pads", "60.00");
    price(product, customer, "100.00");
    inventory(product, "10");
    margin(product, "20.00", "25.00");
    QuoteTransactionResponse quote = quoteDraftService.createFromRfq(command(tenantId, "CUST-1", "BRK-001", "2", "0.00", "clean-" + UUID.randomUUID()));
    return new Scenario(quote, product, null);
  }

  private Scenario unresolvedQuoteScenario() {
    UUID tenantId = tenant();
    QuoteTransactionResponse quote = quoteDraftService.createFromRfq(command(tenantId, "MISSING", "UNKNOWN-SKU", "1", "0.00", "unresolved-" + UUID.randomUUID()));
    return new Scenario(quote, null, null);
  }

  private Scenario pricedQuoteWithInvalidLine() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product product = product("BRK-001", "Brake pads", "60.00");
    price(product, customer, "100.00");
    inventory(product, "10");
    margin(product, "20.00", "25.00");
    QuoteTransactionResponse quote = quoteDraftService.createFromRfq(command(tenantId, "CUST-1", "BRK-001", "0", "0.00", "invalid-line-" + UUID.randomUUID()));
    return new Scenario(quote, product, null);
  }

  private Scenario substituteScenario(boolean blocked, boolean risky) {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product original = product("OEM-BPAD", "OEM brake pads Toyota Camry 2018", "60.00");
    Product substitute = product(blocked ? "BLOCKED-BPAD" : risky ? "RISK-BPAD" : "SAFE-BPAD", "Substitute brake pads Toyota Camry 2018", "50.00");
    price(original, customer, "100.00");
    price(substitute, customer, "90.00");
    inventory(original, "0");
    inventory(substitute, "20");
    substitutes.save(new ProductSubstitute(tenantId, original.getId(), substitute.getId(), "COMPATIBLE_ALTERNATIVE", risky ? "HIGH" : "LOW", risky, "Substitute", NOW));
    compatibility.save(new ProductCompatibility(tenantId, substitute.getId(), "VEHICLE", "Toyota", "Camry", 2018, 2018, null, "Verified", risky ? "HIGH" : "LOW", NOW));
    if (blocked) {
      substitutionPreferences.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), original.getId(), null, false, null, substitute.getId(), "Blocked", NOW));
    }
    margin(original, "20.00", "25.00");
    QuoteTransactionResponse quote = quoteDraftService.createFromRfq(command(tenantId, "CUST-1", "OEM-BPAD", "5", "0.00", "sub-" + UUID.randomUUID()));
    return new Scenario(quote, original, substitute);
  }

  private Scenario marginRiskScenario() {
    UUID tenantId = tenant();
    CustomerAccount customer = customer("CUST-1", "ACME", "Acme Parts");
    Product product = product("MARGIN-SKU", "Margin item", "80.00");
    price(product, customer, "100.00");
    inventory(product, "10");
    margin(product, "20.00", "25.00");
    QuoteTransactionResponse quote = quoteDraftService.createFromRfq(command(tenantId, "CUST-1", "MARGIN-SKU", "1", "30.00", "margin-" + UUID.randomUUID()));
    return new Scenario(quote, product, null);
  }

  private UUID tenant() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    locations.save(new Location(tenantId, "ALM", "Almaty", "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    return tenantId;
  }

  private CustomerAccount customer(String externalRef, String code, String name) {
    return customers.save(new CustomerAccount(TenantContext.requireTenantId(), externalRef, code, name, name, null, "ACTIVE", "USD", null, NOW));
  }

  private Product product(String sku, String name, String cost) {
    Product product = products.save(new Product(TenantContext.requireTenantId(), sku, name, null, "Brake", null, null, "EA", "ACTIVE", new BigDecimal(cost), "USD", NOW));
    aliases.save(new ProductAlias(TenantContext.requireTenantId(), product.getId(), "SKU", sku, ProductCodeNormalizer.normalize(sku), null, BigDecimal.ONE, NOW));
    return product;
  }

  private void price(Product product, CustomerAccount customer, String unitPrice) {
    prices.save(new PriceRule(TenantContext.requireTenantId(), product.getId(), customer == null ? null : customer.getId(), null, null, BigDecimal.ONE, "EA", new BigDecimal(unitPrice), "USD", NOW, null, 10, NOW));
  }

  private void inventory(Product product, String available) {
    inventory.save(new InventorySnapshot(TenantContext.requireTenantId(), product.getId(), locations.findByTenantIdAndCode(TenantContext.requireTenantId(), "ALM").orElseThrow().getId(), new BigDecimal(available), new BigDecimal(available), BigDecimal.ZERO, NOW, "TEST", null, NOW));
  }

  private void margin(Product product, String minimum, String approvalBelow) {
    margins.save(new MarginRule(TenantContext.requireTenantId(), "M-" + product.getSku(), "Margin " + product.getSku(), product.getId(), null, null, new BigDecimal(minimum), new BigDecimal(approvalBelow), NOW));
  }

  private CreateDraftQuoteFromRfqCommand command(UUID tenantId, String customerExternalRef, String sku, String quantity, String discount, String idempotencyKey) {
    return new CreateDraftQuoteFromRfqCommand(tenantId, UUID.randomUUID(), "OPERATOR", customerExternalRef, null, List.of(new RequestedItem(sku, "Requested " + sku + " Toyota Camry 2018", new BigDecimal(quantity), "EA")), "ALM", new BigDecimal(discount), idempotencyKey);
  }

  private record Scenario(QuoteTransactionResponse quote, Product product, Product substitute) {}
}
