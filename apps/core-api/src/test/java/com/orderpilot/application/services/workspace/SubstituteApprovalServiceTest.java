package com.orderpilot.application.services.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.Stage11ADtos.*;
import com.orderpilot.application.services.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.audit.AuditEventRepository;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.integration.CompensationPlanRepository;
import com.orderpilot.domain.integration.ConnectorCommandRepository;
import com.orderpilot.domain.integration.ConnectorSandboxExecutionRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.pricing.MarginRuleRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.*;
import com.orderpilot.domain.workspace.*;
import com.orderpilot.infrastructure.config.CoreConfiguration;
import com.orderpilot.security.policy.TenantPolicyException;
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
@Import({SubstituteApprovalService.class, QuoteLifecycleService.class, RfqToDraftQuoteService.class, ProductCatalogMatchingService.class, ProductSubstitutionService.class, TenantPolicyService.class, AuditEventService.class, CoreConfiguration.class})
class SubstituteApprovalServiceTest {
  private static final Instant NOW = Instant.parse("2026-05-20T00:00:00Z");

  @Autowired private SubstituteApprovalService service;
  @Autowired private RfqToDraftQuoteService rfqService;
  @Autowired private CustomerAccountRepository customerRepository;
  @Autowired private ProductRepository productRepository;
  @Autowired private ProductSubstituteRepository substituteRepository;
  @Autowired private ProductCompatibilityRepository compatibilityRepository;
  @Autowired private CustomerSubstitutionPreferenceRepository preferenceRepository;
  @Autowired private PriceRuleRepository priceRuleRepository;
  @Autowired private InventorySnapshotRepository inventoryRepository;
  @Autowired private DraftQuoteRepository quoteRepository;
  @Autowired private DraftQuoteLineRepository lineRepository;
  @Autowired private QuoteValidationIssueRepository issueRepository;
  @Autowired private AuditEventRepository auditEventRepository;
  @Autowired private ConnectorCommandRepository connectorCommandRepository;
  @Autowired private ConnectorSandboxExecutionRepository sandboxExecutionRepository;
  @Autowired private CompensationPlanRepository compensationPlanRepository;
  @Autowired private MarginRuleRepository marginRuleRepository;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void approveValidSubstituteResolvesIssuesAndMovesQuoteReadyWithoutExternalCommands() {
    Scenario s = scenario(false, false);
    UUID actorId = UUID.randomUUID();

    DraftQuoteResponse approved = service.approveSubstitute(s.quoteId(), s.lineId(), new SubstituteDecisionCommand(actorId, "OPERATOR", s.substituteA().getId(), "Approve RoadMax"));

    assertThat(approved.status()).isEqualTo("READY_FOR_APPROVAL");
    assertThat(approved.lines().get(0).substituteDecisionStatus()).isEqualTo("SUBSTITUTE_APPROVED");
    // Wave 01H Category D: the line response exposes a safe business-facing decision summary (status +
    // decided-at), not the raw internal actor id. The deciding actor is proven via the audit event below.
    assertThat(approved.lines().get(0).substituteDecidedAt()).isNotNull();
    assertThat(lineRepository.findByTenantIdAndDraftQuoteId(TenantContext.requireTenantId(), s.quoteId()).get(0).getSelectedSubstituteProductId()).isEqualTo(s.substituteA().getId());
    assertThat(issueRepository.findByTenantIdAndDraftQuoteIdOrderByCreatedAtAsc(TenantContext.requireTenantId(), s.quoteId()).stream().filter(i -> i.getIssueCode().equals("INSUFFICIENT_STOCK")).findFirst().orElseThrow().getStatus()).isEqualTo("RESOLVED");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("SUBSTITUTE_CANDIDATE_APPROVED");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId()))
        .anyMatch(event -> "SUBSTITUTE_CANDIDATE_APPROVED".equals(event.getAction())
            && actorId.equals(event.getActorId()));
    assertThat(connectorCommandRepository.count()).isZero();
    assertThat(sandboxExecutionRepository.count()).isZero();
    assertThat(compensationPlanRepository.count()).isZero();
    assertThat(marginRuleRepository.count()).isZero();
  }

  @Test
  void rejectSubstituteKeepsLineUnresolvedWhenNoSafeCandidateRemains() {
    Scenario s = scenario(false, false);

    DraftQuoteResponse response = service.rejectSubstitute(s.quoteId(), s.lineId(), new SubstituteDecisionCommand(UUID.randomUUID(), "OPERATOR", s.substituteA().getId(), "Customer wants OE only"));

    assertThat(response.status()).isEqualTo("NEEDS_REVIEW");
    assertThat(response.lines().get(0).substituteDecisionStatus()).isEqualTo("NO_SAFE_SUBSTITUTE_FOUND");
    assertThat(response.issues()).extracting("issueCode").contains("NO_SAFE_SUBSTITUTE_FOUND");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action").contains("SUBSTITUTE_CANDIDATE_REJECTED");
  }

  @Test
  void blockedCustomerSubstituteCannotBeApproved() {
    Scenario s = scenario(true, false);

    assertThatThrownBy(() -> service.approveSubstitute(s.quoteId(), s.lineId(), new SubstituteDecisionCommand(UUID.randomUUID(), "OWNER_ADMIN", s.substituteB().getId(), "Try blocked")))
        .isInstanceOf(QuoteLifecycleViolation.class)
        .hasMessageContaining("Blocked customer substitute");
  }

  @Test
  void substituteFromAnotherTenantCannotBeApprovedForQuoteLine() {
    Scenario s = scenario(false, false);
    UUID tenantB = UUID.randomUUID();
    Product otherTenantSubstitute = product(tenantB, "TENANT-B-SUB", "Tenant B Substitute");

    assertThatThrownBy(() -> service.approveSubstitute(s.quoteId(), s.lineId(), new SubstituteDecisionCommand(UUID.randomUUID(), "OPERATOR", otherTenantSubstitute.getId(), "Cross tenant")))
        .isInstanceOf(QuoteLifecycleViolation.class)
        .hasMessageContaining("not attached");
  }

  @Test
  void quoteCannotBeApprovedWhileSubstituteDecisionIsPending() {
    Scenario s = scenario(false, false);

    assertThatThrownBy(() -> service.approveQuote(s.quoteId(), new QuoteLifecycleCommand(UUID.randomUUID(), "SALES_QUOTE_MANAGER", "Approve")))
        .isInstanceOf(QuoteLifecycleViolation.class)
        .hasMessageContaining("unresolved blocking");
  }

  @Test
  void quoteCanBeMarkedReadyAndApprovedInternallyAfterSubstituteApproval() {
    Scenario s = scenario(false, false);
    service.approveSubstitute(s.quoteId(), s.lineId(), new SubstituteDecisionCommand(UUID.randomUUID(), "OPERATOR", s.substituteA().getId(), "Approve RoadMax"));

    DraftQuoteResponse ready = service.markReady(s.quoteId(), new QuoteLifecycleCommand(UUID.randomUUID(), "OPERATOR", "All substitute checks resolved"));
    DraftQuoteResponse approved = service.approveQuote(s.quoteId(), new QuoteLifecycleCommand(UUID.randomUUID(), "SALES_QUOTE_MANAGER", "Internal approval only"));

    assertThat(ready.status()).isEqualTo("READY_FOR_APPROVAL");
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(auditEventRepository.findByTenantIdOrderByOccurredAtDesc(TenantContext.requireTenantId())).extracting("action")
        .contains("QUOTE_MARKED_READY_FOR_APPROVAL", "QUOTE_APPROVED_INTERNAL");
    assertThat(connectorCommandRepository.count()).isZero();
  }

  @Test
  void highRiskSubstituteRequiresSensitiveApprovalRole() {
    Scenario s = scenario(false, true);

    assertThatThrownBy(() -> service.approveSubstitute(s.quoteId(), s.lineId(), new SubstituteDecisionCommand(UUID.randomUUID(), "OPERATOR", s.substituteA().getId(), "Risky")))
        .isInstanceOf(TenantPolicyException.class);
  }

  private Scenario scenario(boolean includeBlocked, boolean risky) {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    customer();
    Product original = product(tenantId, "TOY-CAM-2018-BPAD-OE", "Original brake pads for Toyota Camry 2018");
    Product substituteA = product(tenantId, "AFT-CAM-2018-BPAD-A", "Aftermarket compatible substitute A");
    Product substituteB = product(tenantId, "AFT-CAM-2018-BPAD-B", "Aftermarket compatible substitute B");
    price(original.getId(), "260.00");
    inventory(original.getId(), "0");
    inventory(substituteA.getId(), "75");
    substituteRepository.save(new ProductSubstitute(tenantId, original.getId(), substituteA.getId(), "COMPATIBLE_ALTERNATIVE", risky ? "HIGH" : "LOW", risky, "RoadMax", NOW));
    compatibilityRepository.save(new ProductCompatibility(tenantId, substituteA.getId(), "VEHICLE", "Toyota", "Camry", 2018, 2018, null, "Verified", risky ? "HIGH" : "LOW", NOW));
    if (includeBlocked) {
      inventory(substituteB.getId(), "4");
      substituteRepository.save(new ProductSubstitute(tenantId, original.getId(), substituteB.getId(), "COMPATIBLE_ALTERNATIVE", "MEDIUM", true, "Blocked option", NOW));
      compatibilityRepository.save(new ProductCompatibility(tenantId, substituteB.getId(), "VEHICLE", "Toyota", "Camry", 2018, 2018, null, "Verified", "MEDIUM", NOW));
      CustomerAccount customer = customerRepository.findByTenantIdAndAccountCodeAndDeletedAtIsNull(tenantId, "ACME").orElseThrow();
      preferenceRepository.save(new CustomerSubstitutionPreference(tenantId, customer.getId(), original.getId(), null, false, null, substituteB.getId(), "Blocked", NOW));
    }

    DraftQuoteResponse quote = rfqService.createFromRfq(new CreateDraftQuoteFromRfqRequest(UUID.randomUUID(), "OPERATOR", "API", null, null, "ACME", null, List.of(new RfqLineInput("Need brake pads for Toyota Camry 2018", "TOY-CAM-2018-BPAD-OE", new BigDecimal("20"), "pcs", null))));
    assertThat(quote.status()).isEqualTo("SUBSTITUTION_REVIEW");
    return new Scenario(quote.id(), quote.lines().get(0).id(), substituteA, substituteB);
  }

  private CustomerAccount customer() {
    return customerRepository.save(new CustomerAccount(TenantContext.requireTenantId(), null, "ACME", "Acme LLP", "Acme", null, "ACTIVE", "USD", null, NOW));
  }

  private Product product(UUID tenantId, String sku, String name) {
    return productRepository.save(new Product(tenantId, sku, name, null, "Brake System", null, null, "EA", "ACTIVE", null, "USD", NOW));
  }

  private PriceRule price(UUID productId, String unitPrice) {
    return priceRuleRepository.save(new PriceRule(TenantContext.requireTenantId(), productId, null, null, null, BigDecimal.ONE, "EA", new BigDecimal(unitPrice), "USD", Instant.parse("2026-01-01T00:00:00Z"), null, 10, NOW));
  }

  private InventorySnapshot inventory(UUID productId, String available) {
    return inventoryRepository.save(new InventorySnapshot(TenantContext.requireTenantId(), productId, UUID.randomUUID(), new BigDecimal(available), new BigDecimal(available), BigDecimal.ZERO, NOW, "TEST", null, NOW));
  }

  private record Scenario(UUID quoteId, UUID lineId, Product substituteA, Product substituteB) {}
}
