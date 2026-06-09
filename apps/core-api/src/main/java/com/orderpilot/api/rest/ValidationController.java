package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.api.dto.Stage5Dtos.*;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationReviewDetailResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewActionResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.application.services.validation.*;
import com.orderpilot.domain.validation.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/validations")
public class ValidationController {
  private final ValidationRunService runService;
  private final ValidationIssueService issueService;
  private final CustomerMatchingService customerMatchingService;
  private final ProductMatchingService productMatchingService;
  private final UomNormalizationService uomNormalizationService;
  private final InventoryValidationService inventoryValidationService;
  private final PricingValidationService pricingValidationService;
  private final DiscountValidationService discountValidationService;
  private final MarginValidationService marginValidationService;
  private final SubstitutionEngineService substitutionEngineService;
  private final ApprovalRequirementService approvalRequirementService;
  private final ExtractionValidationService extractionValidationService;
  private final AdvisoryExtractionValidationHandoffService advisoryValidationHandoffService;
  private final ValidationReviewQueryService validationReviewQueryService;
  private final ValidationReviewCommandService validationReviewCommandService;

  public ValidationController(ValidationRunService runService, ValidationIssueService issueService, CustomerMatchingService customerMatchingService, ProductMatchingService productMatchingService, UomNormalizationService uomNormalizationService, InventoryValidationService inventoryValidationService, PricingValidationService pricingValidationService, DiscountValidationService discountValidationService, MarginValidationService marginValidationService, SubstitutionEngineService substitutionEngineService, ApprovalRequirementService approvalRequirementService, ExtractionValidationService extractionValidationService, AdvisoryExtractionValidationHandoffService advisoryValidationHandoffService, ValidationReviewQueryService validationReviewQueryService, ValidationReviewCommandService validationReviewCommandService) {
    this.runService=runService; this.issueService=issueService; this.customerMatchingService=customerMatchingService; this.productMatchingService=productMatchingService; this.uomNormalizationService=uomNormalizationService; this.inventoryValidationService=inventoryValidationService; this.pricingValidationService=pricingValidationService; this.discountValidationService=discountValidationService; this.marginValidationService=marginValidationService; this.substitutionEngineService=substitutionEngineService; this.approvalRequirementService=approvalRequirementService; this.extractionValidationService=extractionValidationService; this.advisoryValidationHandoffService=advisoryValidationHandoffService; this.validationReviewQueryService=validationReviewQueryService; this.validationReviewCommandService=validationReviewCommandService;
  }

  /**
   * OP-CAP-14C — operator correction command. Submits a bounded correction for an advisory extracted
   * field or line item belonging to {@code validationRunId}. Tenant resolved server-side; non-GET under
   * {@code /api/v1/validations/{id}/review} requires {@code REVIEW_ACTION}. Mutates advisory extraction
   * rows + review/audit state only — no quote/order/ERP/connector/master-data write. Returns a bounded
   * action result (no raw payload).
   */
  @PostMapping("/{validationRunId}/review/corrections")
  public ValidationReviewActionResult submitCorrection(@PathVariable UUID validationRunId, @RequestBody ValidationReviewCorrectionRequest request) {
    return validationReviewCommandService.submitCorrection(validationRunId, request);
  }

  /** OP-CAP-14C — resolve / ignore / escalate a validation issue (tenant-scoped, state-checked, audited). */
  @PostMapping("/{validationRunId}/review/issues/{issueId}/resolution")
  public ValidationReviewActionResult resolveIssue(@PathVariable UUID validationRunId, @PathVariable UUID issueId, @RequestBody ValidationIssueResolutionRequest request) {
    return validationReviewCommandService.resolveIssue(validationRunId, issueId, request);
  }

  /** OP-CAP-14C — raise a minimal pending approval request (reuses existing approval infrastructure). */
  @PostMapping("/{validationRunId}/review/approval-requests")
  public ValidationReviewActionResult requestApproval(@PathVariable UUID validationRunId, @RequestBody ValidationApprovalRequestCommand request) {
    return validationReviewCommandService.requestApproval(validationRunId, request);
  }

  /**
   * OP-CAP-14A — read-only operator review detail for a tenant-scoped validation run. Tenant is
   * resolved server-side ({@code X-Tenant-Id}); a missing or foreign-tenant run returns a bounded 404.
   * Composes the persisted deterministic artifacts (extraction summary, run summary, fields, line
   * items, issues, bounded source evidence, bounded audit metadata, declarative next-action hints)
   * into {@link ValidationReviewDetailResponse}. GET under {@code /api/v1/validations} requires
   * {@code VALIDATION_READ}. Read-only — triggers no handoff/validation/draft/connector action and
   * returns no raw advisory payload, document body, prompt, secrets or stack traces.
   */
  @GetMapping("/{validationRunId}/review")
  public ValidationReviewDetailResponse reviewByRun(@PathVariable UUID validationRunId) {
    return validationReviewQueryService.reviewByValidationRun(validationRunId);
  }

  /** OP-CAP-14A — same bounded review detail resolved from the latest validation run of an extraction result. */
  @GetMapping("/extractions/{extractionResultId}/review")
  public ValidationReviewDetailResponse reviewByExtraction(@PathVariable UUID extractionResultId) {
    return validationReviewQueryService.reviewByExtractionResult(extractionResultId);
  }

  /**
   * OP-CAP-13B — guarded operator/admin trigger to (re-)run the advisory→deterministic validation
   * handoff for a persisted AI-worker advisory extraction result. Tenant is resolved server-side
   * ({@code X-Tenant-Id}); a foreign-tenant result is not found and fails closed. Non-GET under
   * {@code /api/v1/validations} requires {@code VALIDATION_RUN} via {@code ApiPermissionInterceptor}.
   * Safe to call repeatedly — the handoff is idempotent. Returns the bounded handoff DTO only (no raw
   * advisory payload, document text, secrets, or stack traces).
   */
  @PostMapping("/advisory-handoff/{extractionResultId}")
  public AdvisoryValidationHandoffResult advisoryHandoff(@PathVariable UUID extractionResultId) {
    return advisoryValidationHandoffService.handoff(extractionResultId);
  }

  @PostMapping("/runs")
  public ValidationRunResponse create(@RequestBody ValidationRunRequest request) { return toRun(runService.run(request.extractionResultId(), request.mode() == null ? "FULL" : request.mode())); }
  @GetMapping("/runs")
  public List<ValidationRunResponse> runs() { return runService.list().stream().map(this::toRun).toList(); }
  @GetMapping("/runs/{id}")
  public ValidationRunResponse run(@PathVariable UUID id) { return toRun(runService.get(id)); }
  @GetMapping("/runs/{id}/summary")
  public ValidationRunService.ValidationSummary summary(@PathVariable UUID id) { return runService.summary(id); }
  @GetMapping("/runs/{id}/issues")
  public List<ValidationIssue> issues(@PathVariable UUID id) { return issueService.list(id); }
  @GetMapping("/runs/{id}/customer-match")
  public CustomerMatchResult customerMatch(@PathVariable UUID id) { return customerMatchingService.get(id); }
  @GetMapping("/runs/{id}/product-matches")
  public List<ProductMatchResult> productMatches(@PathVariable UUID id) { return productMatchingService.list(id); }
  @GetMapping("/runs/{id}/uom-normalizations")
  public List<UomNormalizationResult> uomNormalizations(@PathVariable UUID id) { return uomNormalizationService.list(id); }
  @GetMapping("/runs/{id}/inventory-checks")
  public List<InventoryCheckResult> inventoryChecks(@PathVariable UUID id) { return inventoryValidationService.list(id); }
  @GetMapping("/runs/{id}/price-checks")
  public List<PriceCheckResult> priceChecks(@PathVariable UUID id) { return pricingValidationService.list(id); }
  @GetMapping("/runs/{id}/discount-checks")
  public List<DiscountCheckResult> discountChecks(@PathVariable UUID id) { return discountValidationService.list(id); }
  @GetMapping("/runs/{id}/margin-checks")
  public List<MarginCheckResult> marginChecks(@PathVariable UUID id) { return marginValidationService.list(id); }
  @GetMapping("/runs/{id}/substitute-candidates")
  public List<SubstituteCandidate> substitutes(@PathVariable UUID id) { return substitutionEngineService.list(id); }
  @GetMapping("/runs/{id}/approval-requirements")
  public List<ApprovalRequirement> approvals(@PathVariable UUID id) { return approvalRequirementService.list(id); }
  @GetMapping("/sources/{sourceType}/{sourceId}/issues")
  public List<ValidationIssue> sourceIssues(@PathVariable String sourceType, @PathVariable UUID sourceId) { return extractionValidationService.issuesBySource(sourceType, sourceId); }
  @PostMapping("/issues/{id}/resolve")
  public ValidationIssue resolve(@PathVariable UUID id) { return issueService.resolve(id); }
  @PostMapping("/issues/{id}/waive")
  public ValidationIssue waive(@PathVariable UUID id) { return issueService.waive(id); }
  @PostMapping("/approval-requirements/{id}/approve")
  public ApprovalRequirement approve(@PathVariable UUID id) { return approvalRequirementService.approve(id); }
  @PostMapping("/approval-requirements/{id}/reject")
  public ApprovalRequirement reject(@PathVariable UUID id) { return approvalRequirementService.reject(id); }

  private ValidationRunResponse toRun(ValidationRun run) {
    return new ValidationRunResponse(run.getId(), run.getExtractionResultId(), run.getSourceType(), run.getStatus(), run.getOverallStatus(), run.getOverallConfidence(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage(), run.getCreatedAt());
  }
}
