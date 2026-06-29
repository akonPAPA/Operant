package com.orderpilot.api.rest;

import com.orderpilot.api.dto.AdvisoryValidationHandoffDtos.AdvisoryValidationHandoffResult;
import com.orderpilot.api.dto.Stage5Dtos.*;
import com.orderpilot.api.dto.ValidationReviewDtos.ValidationReviewDetailResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationApprovalRequestCommand;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationIssueResolutionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewActionResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewCorrectionRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftResult;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRequest;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftStatus;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftabilityResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftQueueResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRecentRemediationRollupResponse;
import com.orderpilot.api.dto.ValidationReviewCommandDtos.ValidationReviewDraftRemediationLineageDetail;
import com.orderpilot.application.services.validation.*;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.validation.*;
import com.orderpilot.security.RequestActorResolver;
import jakarta.servlet.http.HttpServletRequest;
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
  private final ValidationReviewDraftCommandService validationReviewDraftCommandService;
  private final ValidationReviewDraftabilityService validationReviewDraftabilityService;
  private final ValidationReviewDraftQueryService validationReviewDraftQueryService;
  private final ValidationReviewDraftRemediationLineageService validationReviewDraftRemediationLineageService;
  private final RequestActorResolver actorResolver;

  public ValidationController(ValidationRunService runService, ValidationIssueService issueService, CustomerMatchingService customerMatchingService, ProductMatchingService productMatchingService, UomNormalizationService uomNormalizationService, InventoryValidationService inventoryValidationService, PricingValidationService pricingValidationService, DiscountValidationService discountValidationService, MarginValidationService marginValidationService, SubstitutionEngineService substitutionEngineService, ApprovalRequirementService approvalRequirementService, ExtractionValidationService extractionValidationService, AdvisoryExtractionValidationHandoffService advisoryValidationHandoffService, ValidationReviewQueryService validationReviewQueryService, ValidationReviewCommandService validationReviewCommandService, ValidationReviewDraftCommandService validationReviewDraftCommandService, ValidationReviewDraftabilityService validationReviewDraftabilityService, ValidationReviewDraftQueryService validationReviewDraftQueryService, ValidationReviewDraftRemediationLineageService validationReviewDraftRemediationLineageService, RequestActorResolver actorResolver) {
    this.runService=runService; this.issueService=issueService; this.customerMatchingService=customerMatchingService; this.productMatchingService=productMatchingService; this.uomNormalizationService=uomNormalizationService; this.inventoryValidationService=inventoryValidationService; this.pricingValidationService=pricingValidationService; this.discountValidationService=discountValidationService; this.marginValidationService=marginValidationService; this.substitutionEngineService=substitutionEngineService; this.approvalRequirementService=approvalRequirementService; this.extractionValidationService=extractionValidationService; this.advisoryValidationHandoffService=advisoryValidationHandoffService; this.validationReviewQueryService=validationReviewQueryService; this.validationReviewCommandService=validationReviewCommandService; this.validationReviewDraftCommandService=validationReviewDraftCommandService; this.validationReviewDraftabilityService=validationReviewDraftabilityService; this.validationReviewDraftQueryService=validationReviewDraftQueryService; this.validationReviewDraftRemediationLineageService=validationReviewDraftRemediationLineageService;
    this.actorResolver = actorResolver;
  }

  /**
   * OP-CAP-15A/15B — create an internal Draft Quote from a validation run review. Tenant resolved
   * server-side; non-GET under {@code /api/v1/validations/{id}/review} requires {@code REVIEW_ACTION}.
   * Readiness-gated (open blocking issues fail closed with 409), idempotent per source review, audited.
   * Optional {@code selectedLineIds} (subset of validated lines) and bounded {@code operatorNote}.
   * Creates an internal draft only — no final/approved order and no ERP/1C/connector write.
   */
  @PostMapping("/{validationRunId}/review/draft-quote")
  public ValidationReviewDraftResult createDraftQuote(@PathVariable UUID validationRunId, @RequestBody(required = false) ValidationReviewDraftRequest request, HttpServletRequest http) {
    return validationReviewDraftCommandService.createDraftQuote(validationRunId, trustedActor(http), selectedLines(request), note(request));
  }

  /** OP-CAP-15A/15B — create an internal Draft Order from a validation run review (same gates/idempotency/audit). */
  @PostMapping("/{validationRunId}/review/draft-order")
  public ValidationReviewDraftResult createDraftOrder(@PathVariable UUID validationRunId, @RequestBody(required = false) ValidationReviewDraftRequest request, HttpServletRequest http) {
    return validationReviewDraftCommandService.createDraftOrder(validationRunId, trustedActor(http), selectedLines(request), note(request));
  }

  /**
   * OP-CAP-15B — read-only draft visibility for the validation review surface. GET under
   * {@code /api/v1/validations} requires {@code VALIDATION_READ}. Tenant-scoped; a foreign-tenant run
   * returns 404. Returns whether a draft already exists with a bounded link/type/id (no write, no audit).
   */
  @GetMapping("/{validationRunId}/review/draft-status")
  public ValidationReviewDraftStatus draftStatus(@PathVariable UUID validationRunId) {
    return validationReviewDraftCommandService.draftStatus(validationRunId);
  }

  /**
   * OP-CAP-15C — advisory per-line draftability hints for the validation review surface. GET under
   * {@code /api/v1/validations} requires {@code VALIDATION_READ}. Tenant-scoped; a foreign-tenant run
   * returns 404. Read-only — creates no draft and no ExceptionCase, emits no audit. Hints are advisory;
   * the create endpoints above re-validate and remain the final authority.
   */
  @GetMapping("/{validationRunId}/review/draftability")
  public ValidationReviewDraftabilityResponse draftability(@PathVariable UUID validationRunId) {
    return validationReviewDraftabilityService.draftability(validationRunId);
  }

  /**
   * OP-CAP-15C — lite, read-only queue of internal drafts created from validation reviews across runs.
   * GET under {@code /api/v1/validations} requires {@code VALIDATION_READ}. Tenant-scoped, paginated,
   * sorted by createdAt desc. Optional {@code draftType} (QUOTE|ORDER) and {@code status} filters;
   * {@code limit} clamped (default 25, max 100). Never exposes raw operator-note content.
   */
  @GetMapping("/review-drafts")
  public ValidationReviewDraftQueueResponse reviewDrafts(
      @RequestParam(name = "draftType", required = false) String draftType,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "offset", required = false) Integer offset) {
    return validationReviewDraftQueryService.reviewDraftQueue(draftType, status, limit, offset);
  }

  @GetMapping("/review-drafts/remediation-rollup")
  public ValidationReviewDraftRecentRemediationRollupResponse reviewDraftsRemediationRollup(
      @RequestParam(name = "limit", required = false) Integer limit) {
    return validationReviewDraftQueryService.recentRemediationRollup(limit);
  }

  /**
   * OP-CAP-15H — read-only remediation lineage DETAIL for one review-origin draft. Makes the OP-CAP-15G
   * queue summary explainable: per draft line, the structured OperatorAction lineage (corrections, issue
   * resolutions, approvals) plus run-scoped structured actions that could not be attached to a draft line.
   * GET under {@code /api/v1/validations} requires {@code VALIDATION_READ}. Tenant-scoped; a missing or
   * foreign-tenant draft returns a bounded 404. {@code draftKind} is QUOTE or ORDER (else 400). Read-only —
   * derives only from structured records with stable ids, exposes no raw note/payload, writes nothing.
   */
  @GetMapping("/review-drafts/{draftKind}/{draftId}/remediation-lineage")
  public ValidationReviewDraftRemediationLineageDetail draftRemediationLineage(
      @PathVariable String draftKind, @PathVariable UUID draftId) {
    return validationReviewDraftRemediationLineageService.remediationLineage(draftKind, draftId);
  }

  private UUID trustedActor(HttpServletRequest http) {
    return actorResolver.resolveVerifiedActor(http, TenantContext.getTenantId().orElse(null));
  }

  private java.util.List<UUID> selectedLines(ValidationReviewDraftRequest request) {
    return request == null ? null : request.selectedLineIds();
  }

  private String note(ValidationReviewDraftRequest request) {
    return request == null ? null : request.operatorNote();
  }

  /**
   * OP-CAP-14C — operator correction command. Submits a bounded correction for an advisory extracted
   * field or line item belonging to {@code validationRunId}. Tenant resolved server-side; non-GET under
   * {@code /api/v1/validations/{id}/review} requires {@code REVIEW_ACTION}. Mutates advisory extraction
   * rows + review/audit state only — no quote/order/ERP/connector/master-data write. Returns a bounded
   * action result (no raw payload).
   */
  @PostMapping("/{validationRunId}/review/corrections")
  public ValidationReviewActionResult submitCorrection(@PathVariable UUID validationRunId, @RequestBody ValidationReviewCorrectionRequest request, HttpServletRequest http) {
    return validationReviewCommandService.submitCorrection(validationRunId, request, trustedActor(http));
  }

  /** OP-CAP-14C — resolve / ignore / escalate a validation issue (tenant-scoped, state-checked, audited). */
  @PostMapping("/{validationRunId}/review/issues/{issueId}/resolution")
  public ValidationReviewActionResult resolveIssue(@PathVariable UUID validationRunId, @PathVariable UUID issueId, @RequestBody ValidationIssueResolutionRequest request, HttpServletRequest http) {
    return validationReviewCommandService.resolveIssue(validationRunId, issueId, request, trustedActor(http));
  }

  /** OP-CAP-14C — raise a minimal pending approval request (reuses existing approval infrastructure). */
  @PostMapping("/{validationRunId}/review/approval-requests")
  public ValidationReviewActionResult requestApproval(@PathVariable UUID validationRunId, @RequestBody ValidationApprovalRequestCommand request, HttpServletRequest http) {
    return validationReviewCommandService.requestApproval(validationRunId, request, trustedActor(http));
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
