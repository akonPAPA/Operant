package com.orderpilot.application.services.validation;

import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.JsonSupport;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.validation.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ValidationRunService {
  private final ValidationRunRepository runRepository; private final ExtractionResultRepository extractionResultRepository; private final ExtractedFieldRepository fieldRepository; private final ExtractedLineItemRepository lineRepository; private final ValidationIssueService issueService; private final CustomerMatchingService customerMatchingService; private final ProductMatchingService productMatchingService; private final UomNormalizationService uomNormalizationService; private final InventoryValidationService inventoryValidationService; private final PricingValidationService pricingValidationService; private final DiscountValidationService discountValidationService; private final MarginValidationService marginValidationService; private final CompatibilityService compatibilityService; private final SubstitutionEngineService substitutionEngineService; private final ApprovalRequirementService approvalRequirementService; private final AuditEventService auditEventService; private final JsonSupport jsonSupport; private final Clock clock;
  public ValidationRunService(ValidationRunRepository runRepository, ExtractionResultRepository extractionResultRepository, ExtractedFieldRepository fieldRepository, ExtractedLineItemRepository lineRepository, ValidationIssueService issueService, CustomerMatchingService customerMatchingService, ProductMatchingService productMatchingService, UomNormalizationService uomNormalizationService, InventoryValidationService inventoryValidationService, PricingValidationService pricingValidationService, DiscountValidationService discountValidationService, MarginValidationService marginValidationService, CompatibilityService compatibilityService, SubstitutionEngineService substitutionEngineService, ApprovalRequirementService approvalRequirementService, AuditEventService auditEventService, JsonSupport jsonSupport, Clock clock) {
    this.runRepository=runRepository; this.extractionResultRepository=extractionResultRepository; this.fieldRepository=fieldRepository; this.lineRepository=lineRepository; this.issueService=issueService; this.customerMatchingService=customerMatchingService; this.productMatchingService=productMatchingService; this.uomNormalizationService=uomNormalizationService; this.inventoryValidationService=inventoryValidationService; this.pricingValidationService=pricingValidationService; this.discountValidationService=discountValidationService; this.marginValidationService=marginValidationService; this.compatibilityService=compatibilityService; this.substitutionEngineService=substitutionEngineService; this.approvalRequirementService=approvalRequirementService; this.auditEventService=auditEventService; this.jsonSupport=jsonSupport; this.clock=clock;
  }

  @Transactional
  public ValidationRun run(UUID extractionResultId, String mode) {
    UUID tenantId = TenantContext.requireTenantId();
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId).orElseThrow();
    ValidationRun run = runRepository.save(new ValidationRun(tenantId, extractionResultId, "EXTRACTION_RESULT", clock.instant()));
    auditEventService.record("VALIDATION_RUN_CREATED", "ValidationRun", run.getId().toString(), null, "{\"extractionResultId\":\"" + extractionResultId + "\"}");
    try {
      run.start(clock.instant());
      auditEventService.record("VALIDATION_RUN_STARTED", "ValidationRun", run.getId().toString(), null, "{\"extractionResultId\":\"" + extractionResultId + "\"}");
      List<ExtractedField> fields = fieldRepository.findByTenantIdAndExtractionResultId(tenantId, extractionResultId);
      List<ExtractedLineItem> lines = lineRepository.findByTenantIdAndExtractionResultId(tenantId, extractionResultId);
      checkExtractionConfidence(run.getId(), extraction, fields, lines);
      CustomerMatchResult customer = "PRODUCT_ONLY".equalsIgnoreCase(mode) ? null : customerMatchingService.match(run.getId(), extractionResultId, fields);
      UUID customerId = customer == null ? null : customer.getMatchedCustomerAccountId();
      Map<String, Object> resultJson = jsonSupport.parseObject(extraction.getResultJson());
      if (!"CUSTOMER_ONLY".equalsIgnoreCase(mode)) for (ExtractedLineItem line : lines) {
        checkLineSanity(run.getId(), extractionResultId, line);
        ProductMatchResult product = "CUSTOMER_ONLY".equalsIgnoreCase(mode) ? null : productMatchingService.match(run.getId(), extractionResultId, line);
        UomNormalizationResult uom = uomNormalizationService.normalize(run.getId(), extractionResultId, line);
        UUID productId = product == null ? null : product.getMatchedProductId();
        InventoryCheckResult inventory = inventoryValidationService.check(run.getId(), extractionResultId, line, productId, customerId);
        PriceCheckResult price = pricingValidationService.check(run.getId(), extractionResultId, line, productId, customerId, uom.getNormalizedUom());
        discountValidationService.check(run.getId(), line.getId(), customerId, productId, discountValidationService.extractRequestedDiscount(resultJson));
        marginValidationService.check(run.getId(), extractionResultId, line.getId(), productId, price.getUnitPrice());
        boolean substitutionNeeded = "OUT_OF_STOCK".equals(inventory.getStatus()) || "LOW_STOCK".equals(inventory.getStatus()) || "INSUFFICIENT_STOCK".equals(inventory.getStatus()) || (product != null && "NOT_FOUND".equals(product.getStatus()));
        substitutionEngineService.generate(run.getId(), extractionResultId, line.getId(), productId, customerId, inventory.getStatus());
        compatibilityService.check(run.getId(), extractionResultId, line.getId(), productId, resultJson, substitutionNeeded);
      }
      finish(run);
      auditEventService.record("VALIDATION_RUN_COMPLETED", "ValidationRun", run.getId().toString(), null, "{\"overallStatus\":\"" + run.getOverallStatus() + "\"}");
      return runRepository.save(run);
    } catch (RuntimeException ex) {
      run.fail(ex.getMessage(), clock.instant());
      auditEventService.record("VALIDATION_RUN_FAILED", "ValidationRun", run.getId().toString(), null, "{\"error\":\"" + ex.getMessage() + "\"}");
      return runRepository.save(run);
    }
  }

  @Transactional(readOnly = true) public List<ValidationRun> list() { return runRepository.findByTenantIdOrderByCreatedAtDesc(TenantContext.requireTenantId()); }
  @Transactional(readOnly = true) public ValidationRun get(UUID id) { return runRepository.findByIdAndTenantId(id, TenantContext.requireTenantId()).orElseThrow(); }
  @Transactional(readOnly = true) public List<ValidationRun> latestForExtraction(UUID extractionResultId) { return runRepository.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(TenantContext.requireTenantId(), extractionResultId); }
  @Transactional(readOnly = true) public ValidationSummary summary(UUID id) {
    ValidationRun run = get(id);
    return new ValidationSummary(run, issueService.list(id), approvalRequirementService.list(id), customerMatchingService.get(id), productMatchingService.list(id), substitutionEngineService.list(id));
  }

  private void finish(ValidationRun run) {
    List<ValidationIssue> issues = issueService.list(run.getId());
    List<ApprovalRequirement> approvals = approvalRequirementService.list(run.getId());
    boolean hasCritical = issues.stream().anyMatch(i -> "CRITICAL".equals(i.getSeverity()));
    boolean hasError = issues.stream().anyMatch(i -> "ERROR".equals(i.getSeverity()));
    boolean hasWarnings = issues.stream().anyMatch(i -> "WARNING".equals(i.getSeverity()));
    String overall = hasCritical ? "BLOCKED" : hasError || !approvals.isEmpty() ? "NEEDS_REVIEW" : hasWarnings ? "VALIDATION_PASSED_WITH_WARNINGS" : "VALIDATION_PASSED";
    BigDecimal confidence = "VALIDATION_PASSED".equals(overall) ? new BigDecimal("0.9500") : "VALIDATION_PASSED_WITH_WARNINGS".equals(overall) ? new BigDecimal("0.8000") : new BigDecimal("0.5000");
    if ("NEEDS_REVIEW".equals(overall)) run.needsReview(overall, confidence, clock.instant()); else run.complete(overall, confidence, clock.instant());
  }

  private void checkExtractionConfidence(UUID runId, ExtractionResult extraction, List<ExtractedField> fields, List<ExtractedLineItem> lines) {
    boolean lowDocument = extraction.getOverallConfidence() != null && extraction.getOverallConfidence().compareTo(new BigDecimal("0.5000")) < 0;
    boolean lowField = fields.stream().anyMatch(f -> f.getConfidence() != null && f.getConfidence().compareTo(new BigDecimal("0.5000")) < 0);
    boolean lowLine = lines.stream().anyMatch(l -> l.getConfidence() != null && l.getConfidence().compareTo(new BigDecimal("0.5000")) < 0);
    if (lowDocument || lowField || lowLine || "needs_review".equalsIgnoreCase(extraction.getValidationStatus())) {
      issueService.open(runId, extraction.getId(), null, null, "LOW_EXTRACTION_CONFIDENCE", "WARNING", "Extraction confidence requires deterministic review before downstream workflow", "{\"advisoryOnly\":true}");
      approvalRequirementService.create(runId, null, "NEEDS_HUMAN_REVIEW", "MEDIUM", "Low extraction confidence requires operator review");
    }
  }

  private void checkLineSanity(UUID runId, UUID extractionResultId, ExtractedLineItem line) {
    if (line.getNormalizedQuantity() == null || line.getNormalizedQuantity().compareTo(BigDecimal.ZERO) <= 0) {
      issueService.open(runId, extractionResultId, line.getId(), null, "INVALID_QUANTITY", "ERROR", "Extracted quantity must be greater than zero", "{\"rawQuantity\":\"" + (line.getRawQuantity() == null ? "" : line.getRawQuantity()) + "\"}");
    }
    LocalDate requestedDate = line.getRequestedDate();
    if (requestedDate != null && requestedDate.isBefore(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))) {
      issueService.open(runId, extractionResultId, line.getId(), null, "REQUESTED_DATE_INVALID", "WARNING", "Requested date is in the past", "{\"requestedDate\":\"" + requestedDate + "\"}");
    }
  }

  public record ValidationSummary(ValidationRun run, List<ValidationIssue> issues, List<ApprovalRequirement> approvals, CustomerMatchResult customerMatch, List<ProductMatchResult> productMatches, List<SubstituteCandidate> substituteCandidates) {}
}
