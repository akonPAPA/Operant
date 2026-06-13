package com.orderpilot.application.services.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftabilityResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewLineDraftability;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewLineRemediation;
import com.orderpilot.application.services.workspace.DraftPreparationBlockedException;
import com.orderpilot.common.errors.NotFoundException;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.customer.CustomerAccount;
import com.orderpilot.domain.customer.CustomerAccountRepository;
import com.orderpilot.domain.extraction.ExtractedField;
import com.orderpilot.domain.extraction.ExtractedFieldRepository;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.inventory.InventorySnapshot;
import com.orderpilot.domain.inventory.InventorySnapshotRepository;
import com.orderpilot.domain.location.Location;
import com.orderpilot.domain.location.LocationRepository;
import com.orderpilot.domain.pricing.PriceRule;
import com.orderpilot.domain.pricing.PriceRuleRepository;
import com.orderpilot.domain.product.Product;
import com.orderpilot.domain.product.ProductRepository;
import com.orderpilot.domain.validation.ValidationIssue;
import com.orderpilot.domain.validation.ValidationIssueRepository;
import com.orderpilot.domain.workspace.DraftQuoteRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * OP-CAP-15D — advisory remediation metadata on draftability hints.
 *
 * <p>Proves blocked/warning lines carry safe, machine-readable remediation pointing at EXISTING OP-CAP-14C
 * actions (resolve issue / correct line), that already-drafted lines offer no remediation, that the read
 * stays side-effect free, that a foreign tenant cannot infer remediation targets, and that the create
 * endpoint still rejects blocked lines regardless of UI.
 */
@SpringBootTest
@ActiveProfiles("test")
class ValidationReviewRemediationStage15DTest {
  private static final Instant NOW = Instant.parse("2026-06-09T00:00:00Z");

  @Autowired private ValidationReviewDraftabilityService draftability;
  @Autowired private ValidationReviewDraftCommandService draftBridge;
  @Autowired private ValidationRunService validationRunService;
  @Autowired private ExtractionResultRepository extractionResults;
  @Autowired private ExtractedFieldRepository fields;
  @Autowired private ExtractedLineItemRepository lines;
  @Autowired private ValidationIssueRepository issues;
  @Autowired private CustomerAccountRepository customers;
  @Autowired private ProductRepository products;
  @Autowired private LocationRepository locations;
  @Autowired private InventorySnapshotRepository inventory;
  @Autowired private PriceRuleRepository prices;
  @Autowired private DraftQuoteRepository draftQuotes;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void blockingIssueLineExposesResolveIssueRemediationWithTargetIssueId() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "RES");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking issue", "{}", NOW));

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.severity()).isEqualTo("BLOCKED");
    ValidationReviewLineRemediation remediation = line.remediations().stream()
        .filter(r -> "RESOLVE_ISSUE".equals(r.remediationType())).findFirst().orElseThrow();
    assertThat(remediation.reasonCode()).isEqualTo("BLOCKING_ISSUE_UNRESOLVED");
    assertThat(remediation.targetIssueId()).isEqualTo(issue.getId());
    assertThat(remediation.targetLineItemId()).isEqualTo(run.lineA);
    assertThat(remediation.recommendedAction()).isNotBlank();
  }

  @Test
  void missingNormalizedUomLineExposesCorrectLineRemediation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Location location = seedCustomer(tenantId, "UOM");
    seedProduct(tenantId, "SKU-UOM", location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extraction.getId(), 1, "SKU-UOM", "SKU-UOM Filter", "2", new BigDecimal("5"), "boxes", null, new BigDecimal("0.95"), null, NOW));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();

    ValidationReviewDraftabilityResponse response = draftability.draftability(runId);

    ValidationReviewLineDraftability hint = response.lines().stream().filter(l -> l.lineItemId().equals(line.getId())).findFirst().orElseThrow();
    ValidationReviewLineRemediation remediation = hint.remediations().stream()
        .filter(r -> "UOM_NOT_NORMALIZED".equals(r.reasonCode())).findFirst().orElseThrow();
    assertThat(remediation.remediationType()).isEqualTo("CORRECT_LINE");
    assertThat(remediation.targetLineItemId()).isEqualTo(line.getId());
    assertThat(remediation.targetIssueId()).isNull();
  }

  @Test
  void quantityNotNormalizedExposesCorrectLineRemediation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = blockedRun(tenantId, "QTY"); // qty 0

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.remediations()).anyMatch(r -> "QUANTITY_NOT_NORMALIZED".equals(r.reasonCode()) && "CORRECT_LINE".equals(r.remediationType()));
  }

  @Test
  void warningLineExposesViewIssueRemediation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "WARN");
    ValidationIssue issue = issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "PRICE_NEAR_FLOOR", "WARNING", "advisory warning", "{}", NOW));

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.severity()).isEqualTo("WARNING");
    ValidationReviewLineRemediation remediation = line.remediations().stream()
        .filter(r -> "VIEW_ISSUE".equals(r.remediationType())).findFirst().orElseThrow();
    assertThat(remediation.targetIssueId()).isEqualTo(issue.getId());
  }

  @Test
  void readyLineHasNoRemediation() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "RDY");

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.severity()).isEqualTo("OK");
    assertThat(line.remediations()).isEmpty();
  }

  @Test
  void alreadyDraftedLineOffersNoRemediationAction() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "DONE");
    draftBridge.createDraftQuote(run.runId, UUID.randomUUID());

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    ValidationReviewLineDraftability line = response.lines().get(0);
    assertThat(line.alreadyDrafted()).isTrue();
    assertThat(line.remediations()).isEmpty();
  }

  @Test
  void draftabilityWithRemediationRemainsReadOnly() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = cleanRun(tenantId, "RO");
    issues.save(new ValidationIssue(tenantId, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));
    long productsBefore = products.count();
    long issuesBefore = issues.count();

    ValidationReviewDraftabilityResponse response = draftability.draftability(run.runId);

    assertThat(response.lines().get(0).remediations()).isNotEmpty();
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
    assertThat(products.count()).isEqualTo(productsBefore);
    assertThat(issues.count()).isEqualTo(issuesBefore); // no new issue/case rows
  }

  @Test
  void foreignTenantCannotInferRemediationTargets() {
    UUID tenantA = UUID.randomUUID();
    TenantContext.setTenantId(tenantA);
    Run run = cleanRun(tenantA, "XS");
    issues.save(new ValidationIssue(tenantA, run.runId, run.extractionResultId, run.lineA, null, "MARGIN_BELOW_GUARDRAIL", "ERROR", "blocking", "{}", NOW));

    TenantContext.setTenantId(UUID.randomUUID());
    assertThatThrownBy(() -> draftability.draftability(run.runId))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("validation_run_not_found");
  }

  @Test
  void createStillRejectsBlockedSelectedLineRegardlessOfRemediationUi() {
    UUID tenantId = UUID.randomUUID();
    TenantContext.setTenantId(tenantId);
    Run run = blockedRun(tenantId, "AUTH");

    assertThatThrownBy(() -> draftBridge.createDraftQuote(run.runId, UUID.randomUUID(), List.of(run.lineA), null))
        .isInstanceOf(DraftPreparationBlockedException.class);
    assertThat(draftQuotes.findByTenantIdOrderByCreatedAtDesc(tenantId)).isEmpty();
  }

  // --- helpers -----------------------------------------------------------------------------------

  private record Run(UUID runId, UUID extractionResultId, UUID lineA) {}

  private Run cleanRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, sku, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), sku, "2", new BigDecimal("5"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, extraction.getId(), lineA);
  }

  private Run blockedRun(UUID tenantId, String tag) {
    String sku = "SKU-" + tag;
    Location location = seedCustomer(tenantId, tag);
    seedProduct(tenantId, sku, location);
    ExtractionResult extraction = extraction(tenantId, "RFQ");
    UUID lineA = saveLine(tenantId, extraction.getId(), sku, "0", new BigDecimal("0"));
    UUID runId = validationRunService.run(extraction.getId(), "FULL").getId();
    return new Run(runId, extraction.getId(), lineA);
  }

  private Location seedCustomer(UUID tenantId, String tag) {
    Location location = locations.save(new Location(tenantId, "LOC-" + tag, "LOC-" + tag, "WAREHOUSE", null, "Almaty", "KZ", true, NOW));
    customers.save(new CustomerAccount(tenantId, null, "ACME-" + tag, "Acme", "Acme", null, "ACTIVE", "USD", location.getId(), NOW));
    return location;
  }

  private void seedProduct(UUID tenantId, String sku, Location location) {
    Product product = products.save(new Product(tenantId, sku, sku + " Filter", sku + " Filter", "Filters", "Brand", "Maker", "EA", "ACTIVE", new BigDecimal("10"), "USD", NOW));
    inventory.save(new InventorySnapshot(tenantId, product.getId(), location.getId(), new BigDecimal("50"), new BigDecimal("50"), BigDecimal.ZERO, NOW, "TEST", null, NOW));
    prices.save(new PriceRule(tenantId, product.getId(), null, null, null, BigDecimal.ONE, "EA", new BigDecimal("25"), "USD", NOW.minusSeconds(60), null, 100, NOW));
  }

  private ExtractionResult extraction(UUID tenantId, String intent) {
    ExtractionResult result = extractionResults.save(new ExtractionResult(tenantId, UUID.randomUUID(), "CHANNEL_MESSAGE", UUID.randomUUID(), intent, "message", new BigDecimal("0.95"), "{}", "ready_for_validation", NOW));
    fields.save(new ExtractedField(tenantId, result.getId(), "customer_hint", "Acme", "Acme", "customer_hint", new BigDecimal("0.95"), null, NOW));
    return result;
  }

  private UUID saveLine(UUID tenantId, UUID extractionResultId, String sku, String rawQty, BigDecimal normalizedQty) {
    ExtractedLineItem line = lines.save(new ExtractedLineItem(tenantId, extractionResultId, 1, sku, sku + " Filter", rawQty, normalizedQty, "EA", "EA", new BigDecimal("0.95"), null, NOW));
    return line.getId();
  }
}
