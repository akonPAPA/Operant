package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage5Dtos.*;
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

  public ValidationController(ValidationRunService runService, ValidationIssueService issueService, CustomerMatchingService customerMatchingService, ProductMatchingService productMatchingService, UomNormalizationService uomNormalizationService, InventoryValidationService inventoryValidationService, PricingValidationService pricingValidationService, DiscountValidationService discountValidationService, MarginValidationService marginValidationService, SubstitutionEngineService substitutionEngineService, ApprovalRequirementService approvalRequirementService) {
    this.runService=runService; this.issueService=issueService; this.customerMatchingService=customerMatchingService; this.productMatchingService=productMatchingService; this.uomNormalizationService=uomNormalizationService; this.inventoryValidationService=inventoryValidationService; this.pricingValidationService=pricingValidationService; this.discountValidationService=discountValidationService; this.marginValidationService=marginValidationService; this.substitutionEngineService=substitutionEngineService; this.approvalRequirementService=approvalRequirementService;
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
