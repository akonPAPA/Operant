package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage5Dtos.*;
import com.orderpilot.application.services.validation.ExtractionValidationService;
import com.orderpilot.application.services.validation.ValidationRunService;
import com.orderpilot.domain.validation.*;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/extractions/results")
public class ExtractionValidationController {
  private final ValidationRunService validationRunService;
  private final ExtractionValidationService extractionValidationService;
  public ExtractionValidationController(ValidationRunService validationRunService, ExtractionValidationService extractionValidationService) { this.validationRunService = validationRunService; this.extractionValidationService = extractionValidationService; }
  @PostMapping("/{id}/run-validation")
  public ValidationRunResponse runValidation(@PathVariable UUID id) {
    ValidationRun run = validationRunService.run(id, "FULL");
    return new ValidationRunResponse(run.getId(), run.getExtractionResultId(), run.getSourceType(), run.getStatus(), run.getOverallStatus(), run.getOverallConfidence(), run.getStartedAt(), run.getFinishedAt(), run.getErrorMessage(), run.getCreatedAt());
  }
  @GetMapping("/{id}/validation")
  public ExtractionValidationResponse latestValidation(@PathVariable UUID id) { return toResponse(extractionValidationService.latestByExtractionResultId(id)); }

  private static ExtractionValidationResponse toResponse(ExtractionValidationResult result) {
    return new ExtractionValidationResponse(
        result.validationRunId(),
        result.runStatus(),
        result.overallStatus(),
        result.routingRecommendation().name(),
        result.lineItems().stream().map(ExtractionValidationController::toResponse).toList(),
        result.validationIssues().stream().map(ExtractionValidationController::toResponse).toList(),
        result.approvalRequirements().stream().map(ExtractionValidationController::toResponse).toList());
  }

  private static LineItemValidationResponse toResponse(LineItemValidationResult line) {
    return new LineItemValidationResponse(
        line.lineNumber(),
        line.rawSku(),
        line.rawDescription(),
        line.rawQuantity(),
        line.rawUom(),
        toResponse(line.matchedProductCandidate()),
        line.normalizedUom(),
        line.stockStatus(),
        line.priceStatus(),
        line.marginStatus(),
        line.substituteCandidates().stream().map(ExtractionValidationController::toResponse).toList(),
        line.validationIssues().stream().map(ExtractionValidationController::toResponse).toList(),
        line.approvalRequirements().stream().map(ExtractionValidationController::toResponse).toList(),
        line.routingRecommendation().name());
  }

  private static ProductMatchCandidateResponse toResponse(ProductMatchCandidate candidate) {
    if (candidate == null) return null;
    return new ProductMatchCandidateResponse(candidate.productId(), candidate.matchType(), candidate.confidence(), candidate.status());
  }

  private static SubstituteCandidateResponse toResponse(SubstituteCandidate candidate) {
    return new SubstituteCandidateResponse(
        candidate.getId(),
        candidate.getSubstituteProductId(),
        candidate.getSubstituteType(),
        candidate.getRiskLevel(),
        candidate.getRankScore(),
        candidate.getReason(),
        candidate.getInventoryStatus(),
        candidate.getMarginStatus(),
        candidate.isRequiresApproval(),
        candidate.getStatus());
  }

  private static ValidationIssueResponse toResponse(ValidationIssue issue) {
    return new ValidationIssueResponse(
        issue.getId(),
        issue.getExtractedLineItemId(),
        issue.getIssueType(),
        issue.getSeverity(),
        issue.getMessage(),
        issue.getStatus(),
        issue.getCreatedAt());
  }

  private static ApprovalRequirementResponse toResponse(ApprovalRequirement requirement) {
    return new ApprovalRequirementResponse(
        requirement.getId(),
        requirement.getExtractedLineItemId(),
        requirement.getRequirementType(),
        requirement.getSeverity(),
        requirement.getReason(),
        requirement.getStatus(),
        requirement.getCreatedAt());
  }
}
