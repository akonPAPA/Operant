package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;

public record ExtractionValidationResult(
    UUID validationRunId,
    UUID extractionResultId,
    String runStatus,
    String overallStatus,
    ValidationRoutingRecommendation routingRecommendation,
    List<LineItemValidationResult> lineItems,
    List<ValidationIssue> validationIssues,
    List<ApprovalRequirement> approvalRequirements
) {}
