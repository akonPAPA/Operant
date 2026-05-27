package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class Stage6Dtos {
  private Stage6Dtos() {}
  public record AssignRequest(UUID userId) {}
  public record StatusRequest(String status) {}
  public record ApprovalDecisionRequest(String targetType, UUID targetId, String decision, String reason, UUID decidedBy) {}
  public record NoteRequest(String targetType, UUID targetId, String noteText, UUID createdBy) {}
  public record ReviewActionRequest(UUID actorUserId, String reason) {}
  public record ReviewNoteRequest(String noteText, UUID createdBy) {}
  public record ConfirmCandidateRequest(UUID suggestedFixId, UUID actorUserId) {}
  public record CorrectUomRequest(UUID lineItemId, String normalizedUom, UUID actorUserId) {}
  public record CorrectQuantityRequest(UUID lineItemId, BigDecimal normalizedQuantity, UUID actorUserId) {}
  public record MapProductRequest(UUID lineItemId, UUID productId, UUID actorUserId) {}
  public record SubstituteDecisionRequest(UUID candidateId, UUID actorUserId, String reason) {}
  public record IssueDispositionRequest(UUID issueId, UUID actorUserId, String reason) {}
  public record BlockingReason(String issueCode, String severity, String reason, String suggestedCorrectionAction) {}
  public record ReviewCaseSummary(UUID id, String caseNumber, UUID validationRunId, UUID extractionResultId, String status, String priority, String severity, String summary, Instant createdAt) {}
  public record ReviewCaseDetail(ReviewCaseSummary reviewCase, ExtractionSummary extraction, ValidationSummary validation, List<IssueGroup> issueGroups, List<IssueStatusReview> issueStatuses, List<ApprovalRequirementReview> approvalRequirements, List<ApprovalRequirementReview> pendingApprovals, List<ApprovalRequirementReview> rejectedApprovals, List<ApprovalRequirementReview> resolvedApprovals, List<SuggestedActionReview> suggestedActions, List<ProductCandidateReview> productCandidates, List<SubstituteCandidateReview> substituteCandidates, List<FieldReview> fields, List<LineItemReview> lineItems, List<NoteReview> notes, List<OperatorActionReview> timeline, List<OperatorActionReview> correctionHistory, boolean draftPreparationAllowed, List<BlockingReason> blockingReasons, ReviewReadiness readiness) {}
  public record ExtractionSummary(UUID id, String sourceType, UUID sourceId, String detectedIntent, String documentType, BigDecimal confidence, String validationStatus) {}
  public record ValidationSummary(UUID id, String status, String overallStatus, BigDecimal confidence, String riskLevel, Instant startedAt, Instant finishedAt) {}
  public record IssueGroup(String group, List<IssueReview> issues) {}
  public record IssueReview(UUID id, UUID extractedLineItemId, UUID extractedFieldId, String issueType, String severity, String status, String message, String detailsJson, String suggestedAction, String riskLevel) {}
  public record IssueStatusReview(UUID issueId, String issueType, String status, boolean blocking, boolean pendingApproval, String lifecycleLabel) {}
  public record ApprovalRequirementReview(UUID id, UUID extractedLineItemId, String requirementType, String severity, String status, String reason, Instant createdAt) {}
  public record SuggestedActionReview(UUID id, UUID validationIssueId, UUID extractedLineItemId, String actionType, String status, BigDecimal confidence, String reason, String suggestionJson) {}
  public record ProductCandidateReview(UUID extractedLineItemId, UUID productId, String sku, String name, String matchType, BigDecimal confidence, String status) {}
  public record SubstituteCandidateReview(UUID id, UUID extractedLineItemId, UUID sourceProductId, UUID substituteProductId, String substituteSku, String substituteName, String substituteType, String riskLevel, BigDecimal rankScore, boolean requiresApproval, String status, String inventoryStatus, String marginStatus, String reason) {}
  public record FieldReview(UUID id, String fieldName, String rawValue, String normalizedValue, BigDecimal confidence, String validationStatus, EvidenceReference evidence) {}
  public record LineItemReview(UUID id, int lineNumber, String rawSku, String rawDescription, String rawQuantity, BigDecimal normalizedQuantity, String rawUom, String normalizedUom, LocalDate requestedDate, BigDecimal confidence, String validationStatus, EvidenceReference evidence) {}
  public record EvidenceReference(UUID id, String sourceType, UUID sourceId, String evidenceType, Integer pageNumber, Integer startOffset, Integer endOffset, String snippet) {}
  public record NoteReview(UUID id, String noteText, UUID createdBy, Instant createdAt) {}
  public record OperatorActionReview(UUID id, String actionType, String message, Instant createdAt) {}
  public record ReviewReadiness(String readinessStatus, boolean draftPreparationAllowed, List<BlockingReason> blockingReasons, List<ApprovalRequirementReview> pendingApprovals, List<ApprovalRequirementReview> rejectedApprovals, List<ApprovalRequirementReview> resolvedApprovals, List<String> nextRequiredActions) {}
  public record DraftPreview(String targetType, boolean draftPreparationAllowed, List<BlockingReason> blockingReasons, ReviewReadiness readiness, List<DraftPreviewLine> lines, BigDecimal subtotal, String currency, boolean externalExecutionDisabled, boolean inventoryReservationDisabled) {}
  public record DraftPreviewLine(UUID extractedLineItemId, int lineNumber, String rawSku, String description, BigDecimal quantity, String uom, UUID productId, String productSku, String productName, UUID substituteProductId, String substituteSku, String substituteName, BigDecimal unitPrice, String currency, BigDecimal marginPercent, BigDecimal discountPercent, String stockStatus, String priceStatus, String marginStatus, String validationStatus) {}
}
