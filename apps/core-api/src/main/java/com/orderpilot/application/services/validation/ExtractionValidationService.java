package com.orderpilot.application.services.validation;

import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.ExtractedLineItem;
import com.orderpilot.domain.extraction.ExtractedLineItemRepository;
import com.orderpilot.domain.extraction.ExtractionResult;
import com.orderpilot.domain.extraction.ExtractionResultRepository;
import com.orderpilot.domain.validation.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionValidationService {
  private final ValidationRunService validationRunService;
  private final ValidationRunRepository runRepository;
  private final ExtractionResultRepository extractionResultRepository;
  private final ExtractedLineItemRepository lineRepository;
  private final ValidationIssueRepository issueRepository;
  private final ProductMatchingService productMatchingService;
  private final UomNormalizationService uomNormalizationService;
  private final InventoryValidationService inventoryValidationService;
  private final PricingValidationService pricingValidationService;
  private final MarginValidationService marginValidationService;
  private final SubstitutionEngineService substitutionEngineService;
  private final ApprovalRequirementService approvalRequirementService;

  public ExtractionValidationService(ValidationRunService validationRunService, ValidationRunRepository runRepository, ExtractionResultRepository extractionResultRepository, ExtractedLineItemRepository lineRepository, ValidationIssueRepository issueRepository, ProductMatchingService productMatchingService, UomNormalizationService uomNormalizationService, InventoryValidationService inventoryValidationService, PricingValidationService pricingValidationService, MarginValidationService marginValidationService, SubstitutionEngineService substitutionEngineService, ApprovalRequirementService approvalRequirementService) {
    this.validationRunService = validationRunService;
    this.runRepository = runRepository;
    this.extractionResultRepository = extractionResultRepository;
    this.lineRepository = lineRepository;
    this.issueRepository = issueRepository;
    this.productMatchingService = productMatchingService;
    this.uomNormalizationService = uomNormalizationService;
    this.inventoryValidationService = inventoryValidationService;
    this.pricingValidationService = pricingValidationService;
    this.marginValidationService = marginValidationService;
    this.substitutionEngineService = substitutionEngineService;
    this.approvalRequirementService = approvalRequirementService;
  }

  @Transactional
  public ExtractionValidationResult validateCompletedExtraction(UUID extractionResultId) {
    ValidationRun run = validationRunService.run(extractionResultId, "FULL");
    return build(run);
  }

  @Transactional
  public ExtractionValidationResult validateCompletedBySource(UUID tenantId, String sourceType, UUID sourceId) {
    UUID currentTenantId = TenantContext.requireTenantId();
    if (!currentTenantId.equals(tenantId)) throw new IllegalArgumentException("Tenant context does not match requested tenant");
    ExtractionResult extraction = extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, sourceType, sourceId).stream()
        .findFirst()
        .orElseThrow();
    return validateCompletedExtraction(extraction.getId());
  }

  @Transactional(readOnly = true)
  public ExtractionValidationResult latestByExtractionResultId(UUID extractionResultId) {
    UUID tenantId = TenantContext.requireTenantId();
    ExtractionResult extraction = extractionResultRepository.findByIdAndTenantId(extractionResultId, tenantId).orElseThrow();
    ValidationRun run = runRepository.findByTenantIdAndExtractionResultIdOrderByCreatedAtDesc(tenantId, extraction.getId()).stream()
        .findFirst()
        .orElseThrow();
    return build(run);
  }

  @Transactional(readOnly = true)
  public List<ValidationIssue> issuesBySource(String sourceType, UUID sourceId) {
    UUID tenantId = TenantContext.requireTenantId();
    List<UUID> extractionResultIds = extractionResultRepository.findByTenantIdAndSourceTypeAndSourceIdOrderByCreatedAtDesc(tenantId, sourceType, sourceId).stream()
        .map(ExtractionResult::getId)
        .toList();
    if (extractionResultIds.isEmpty()) return List.of();
    return issueRepository.findByTenantIdAndExtractionResultIdInOrderByCreatedAtAsc(tenantId, extractionResultIds);
  }

  private ExtractionValidationResult build(ValidationRun run) {
    List<ValidationIssue> issues = issueRepository.findByTenantIdAndValidationRunIdOrderByCreatedAtAsc(run.getTenantId(), run.getId());
    List<ApprovalRequirement> approvals = approvalRequirementService.list(run.getId());
    List<ExtractedLineItem> lines = lineRepository.findByTenantIdAndExtractionResultId(run.getTenantId(), run.getExtractionResultId()).stream()
        .sorted(Comparator.comparingInt(ExtractedLineItem::getLineNumber))
        .toList();
    Map<UUID, ProductMatchResult> productMatches = byLine(productMatchingService.list(run.getId()), ProductMatchResult::getExtractedLineItemId);
    Map<UUID, UomNormalizationResult> uomResults = byLine(uomNormalizationService.list(run.getId()), UomNormalizationResult::getExtractedLineItemId);
    Map<UUID, InventoryCheckResult> inventoryResults = byLine(inventoryValidationService.list(run.getId()), InventoryCheckResult::getExtractedLineItemId);
    Map<UUID, PriceCheckResult> priceResults = byLine(pricingValidationService.list(run.getId()), PriceCheckResult::getExtractedLineItemId);
    Map<UUID, MarginCheckResult> marginResults = byLine(marginValidationService.list(run.getId()), MarginCheckResult::getExtractedLineItemId);
    Map<UUID, List<SubstituteCandidate>> substitutes = substitutionEngineService.list(run.getId()).stream().collect(Collectors.groupingBy(SubstituteCandidate::getExtractedLineItemId));
    Map<UUID, List<ValidationIssue>> issuesByLine = issues.stream().filter(i -> i.getExtractedLineItemId() != null).collect(Collectors.groupingBy(ValidationIssue::getExtractedLineItemId));
    Map<UUID, List<ApprovalRequirement>> approvalsByLine = approvals.stream().filter(a -> a.getExtractedLineItemId() != null).collect(Collectors.groupingBy(ApprovalRequirement::getExtractedLineItemId));
    List<LineItemValidationResult> lineResults = lines.stream()
        .map(line -> buildLine(line, productMatches.get(line.getId()), uomResults.get(line.getId()), inventoryResults.get(line.getId()), priceResults.get(line.getId()), marginResults.get(line.getId()), substitutes.getOrDefault(line.getId(), List.of()), issuesByLine.getOrDefault(line.getId(), List.of()), approvalsByLine.getOrDefault(line.getId(), List.of())))
        .toList();
    return new ExtractionValidationResult(run.getId(), run.getExtractionResultId(), run.getStatus(), run.getOverallStatus(), route(issues, approvals), lineResults, issues, approvals);
  }

  private LineItemValidationResult buildLine(ExtractedLineItem line, ProductMatchResult product, UomNormalizationResult uom, InventoryCheckResult inventory, PriceCheckResult price, MarginCheckResult margin, List<SubstituteCandidate> substitutes, List<ValidationIssue> issues, List<ApprovalRequirement> approvals) {
    ProductMatchCandidate candidate = product == null ? null : new ProductMatchCandidate(product.getMatchedProductId(), product.getMatchType(), product.getConfidence(), product.getStatus());
    return new LineItemValidationResult(
        line.getId(),
        line.getLineNumber(),
        line.getRawSku(),
        line.getRawDescription(),
        line.getRawQuantity(),
        line.getRawUom(),
        candidate,
        uom == null ? null : uom.getNormalizedUom(),
        inventory == null ? null : inventory.getStatus(),
        price == null ? null : price.getStatus(),
        margin == null ? null : margin.getStatus(),
        substitutes,
        issues,
        approvals,
        route(issues, approvals));
  }

  private ValidationRoutingRecommendation route(List<ValidationIssue> issues, List<ApprovalRequirement> approvals) {
    boolean blocked = issues.stream().anyMatch(i -> "CRITICAL".equals(i.getSeverity()) || "ERROR".equals(i.getSeverity()));
    if (blocked) return ValidationRoutingRecommendation.BLOCKED_UNTIL_FIXED;
    if (!approvals.isEmpty() || issues.stream().anyMatch(i -> "WARNING".equals(i.getSeverity()))) return ValidationRoutingRecommendation.NEEDS_OPERATOR_REVIEW;
    return ValidationRoutingRecommendation.AUTO_READY_DRAFT_ALLOWED;
  }

  private <T> Map<UUID, T> byLine(List<T> values, Function<T, UUID> lineId) {
    return values.stream().collect(Collectors.toMap(lineId, Function.identity(), (first, ignored) -> first));
  }
}
