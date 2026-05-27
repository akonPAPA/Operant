package com.orderpilot.domain.validation;

import java.util.List;
import java.util.UUID;

public record LineItemValidationResult(
    UUID extractedLineItemId,
    int lineNumber,
    String rawSku,
    String rawDescription,
    String rawQuantity,
    String rawUom,
    ProductMatchCandidate matchedProductCandidate,
    String normalizedUom,
    String stockStatus,
    String priceStatus,
    String marginStatus,
    List<SubstituteCandidate> substituteCandidates,
    List<ValidationIssue> validationIssues,
    List<ApprovalRequirement> approvalRequirements,
    ValidationRoutingRecommendation routingRecommendation
) {}
