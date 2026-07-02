package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class Stage6Dtos {
  private Stage6Dtos() {}
  /** Self-assignment carries no client-owned user authority; the backend resolves the actor. */
  public record AssignRequest() {}
  public record StatusRequest(String status) {}
  // OP-CAP-16J: client-owned authority field removed — backend resolves actor from trusted context.
  public record ApprovalDecisionRequest(String targetType, UUID targetId, String decision, String reason) {}
  // Wave 01H Category C: public request records carry business intent only. The acting user is
  // backend-owned authority resolved server-side from the trusted actor context (RequestActorResolver),
  // never from the request body. A body-supplied actor field is ignored (unknown property).
  public record NoteRequest(String targetType, UUID targetId, String noteText) {}
  public record ReviewActionRequest(String reason) {}
  public record ReviewNoteRequest(String noteText) {}
  public record ConfirmCandidateRequest(UUID suggestedFixId) {}
  public record CorrectUomRequest(UUID lineItemId, String normalizedUom) {}
  public record CorrectQuantityRequest(UUID lineItemId, BigDecimal normalizedQuantity) {}
  public record MapProductRequest(UUID lineItemId, UUID productId) {}
  public record SubstituteDecisionRequest(UUID candidateId, String reason) {}
  public record IssueDispositionRequest(UUID issueId, String reason) {}
  public record BlockingReason(String issueCode, String severity, String reason, String suggestedCorrectionAction) {}
  public record ReviewCaseSummary(UUID id, String caseNumber, UUID validationRunId, UUID extractionResultId, String status, String priority, String severity, String summary, Instant createdAt) {}
  public record ReviewCaseDetail(ReviewCaseSummary reviewCase, ExtractionSummary extraction, ValidationSummary validation, List<IssueGroup> issueGroups, List<IssueStatusReview> issueStatuses, List<ApprovalRequirementReview> approvalRequirements, List<ApprovalRequirementReview> pendingApprovals, List<ApprovalRequirementReview> rejectedApprovals, List<ApprovalRequirementReview> resolvedApprovals, List<SuggestedActionReview> suggestedActions, List<ProductCandidateReview> productCandidates, List<SubstituteCandidateReview> substituteCandidates, List<FieldReview> fields, List<LineItemReview> lineItems, List<NoteReview> notes, List<OperatorActionReview> timeline, List<OperatorActionReview> correctionHistory, boolean draftPreparationAllowed, List<BlockingReason> blockingReasons, ReviewReadiness readiness) {}
  public record ExtractionSummary(UUID id, String sourceType, UUID sourceId, String detectedIntent, String documentType, BigDecimal confidence, String validationStatus) {}
  public record ValidationSummary(UUID id, String status, String overallStatus, BigDecimal confidence, String riskLevel, Instant startedAt, Instant finishedAt) {}
  public record IssueGroup(String group, List<IssueReview> issues) {}
  public record IssueReview(UUID id, UUID extractedLineItemId, UUID extractedFieldId, String issueType, String severity, String status, String message, String suggestedAction, String riskLevel) {}
  public record IssueStatusReview(UUID issueId, String issueType, String status, boolean blocking, boolean pendingApproval, String lifecycleLabel) {}
  public record ApprovalRequirementReview(UUID id, UUID extractedLineItemId, String requirementType, String severity, String status, String reason, Instant createdAt) {}
  public record SuggestedActionReview(UUID id, UUID validationIssueId, UUID extractedLineItemId, String actionType, String status, BigDecimal confidence, String reason) {}
  public record ProductCandidateReview(UUID extractedLineItemId, UUID productId, String sku, String name, String matchType, BigDecimal confidence, String status) {}
  public record SubstituteCandidateReview(UUID id, UUID extractedLineItemId, UUID sourceProductId, UUID substituteProductId, String substituteSku, String substituteName, String substituteType, String riskLevel, BigDecimal rankScore, boolean requiresApproval, String status, String inventoryStatus, String marginStatus, String reason) {}
  public record FieldReview(UUID id, String fieldName, String rawValue, String normalizedValue, BigDecimal confidence, String validationStatus, EvidenceReference evidence) {}
  public record LineItemReview(UUID id, int lineNumber, String rawSku, String rawDescription, String rawQuantity, BigDecimal normalizedQuantity, String rawUom, String normalizedUom, LocalDate requestedDate, BigDecimal confidence, String validationStatus, EvidenceReference evidence) {}
  public record EvidenceReference(UUID id, String sourceType, UUID sourceId, String evidenceType, Integer pageNumber, Integer startOffset, Integer endOffset, String snippet) {}
  public record NoteReview(UUID id, String noteText, Instant createdAt) {}
  public record OperatorActionReview(UUID id, String actionType, String message, Instant createdAt) {}
  public record ReviewReadiness(String readinessStatus, boolean draftPreparationAllowed, List<BlockingReason> blockingReasons, List<ApprovalRequirementReview> pendingApprovals, List<ApprovalRequirementReview> rejectedApprovals, List<ApprovalRequirementReview> resolvedApprovals, List<String> nextRequiredActions) {}
  public record DraftPreview(String targetType, boolean draftPreparationAllowed, List<BlockingReason> blockingReasons, ReviewReadiness readiness, List<DraftPreviewLine> lines, BigDecimal subtotal, String currency, boolean externalExecutionDisabled, boolean inventoryReservationDisabled) {}
  public record DraftPreviewLine(UUID extractedLineItemId, int lineNumber, String rawSku, String description, BigDecimal quantity, String uom, UUID productId, String productSku, String productName, UUID substituteProductId, String substituteSku, String substituteName, BigDecimal unitPrice, String currency, BigDecimal marginPercent, BigDecimal discountPercent, String stockStatus, String priceStatus, String marginStatus, String validationStatus) {}
  // OP-CAP-09A: bounded result of preparing exactly one internal draft from an approved validation handoff (review case).
  public record DraftPreparationResult(String draftType, UUID draftId, UUID sourceHandoffId, String status, boolean created, boolean alreadyExisted, String externalExecution, String nextAction) {}
  // OP-CAP-09B: bounded line-level operator draft review surfaces. No raw AI result JSON / document text / message text.
  public record DraftQuoteLineView(UUID lineId, int lineNumber, UUID productId, String rawSku, String normalizedSku, String productName, String description, BigDecimal quantity, String uom, BigDecimal unitPrice, BigDecimal discountPercent, BigDecimal lineTotal, BigDecimal marginPercent, String status, String validationStatus) {}
  public record DraftOrderLineView(UUID lineId, int lineNumber, UUID productId, String description, BigDecimal quantity, String uom, BigDecimal unitPrice, BigDecimal discountPercent, BigDecimal lineTotal, BigDecimal marginPercent, String status, String validationStatus) {}
  public record DraftQuoteDetail(UUID draftId, UUID sourceReviewCaseId, UUID sourceValidationRunId, UUID customerAccountId, String customerDisplayName, String status, String validationStatus, boolean requiresHumanReview, String currency, BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount, BigDecimal marginPercent, int lineCount, List<DraftQuoteLineView> lines, String externalExecution, Instant createdAt) {}
  public record DraftOrderDetail(UUID draftId, UUID sourceReviewCaseId, UUID sourceValidationRunId, UUID customerAccountId, String status, String currency, BigDecimal subtotalAmount, BigDecimal discountAmount, BigDecimal totalAmount, BigDecimal marginPercent, int lineCount, List<DraftOrderLineView> lines, String externalExecution, Instant createdAt) {}
  // Wave 01H Category C: line correction request carries business correction intent only. The
  // operator (actor) is backend-owned authority resolved server-side from the trusted actor context
  // and passed explicitly to DraftReviewService; it is never accepted from the request body.
  public record DraftLineCorrectionRequest(BigDecimal quantity, String uom, String description, BigDecimal unitPrice, UUID productId, String correctionReason) {
    public boolean hasAnyField() { return quantity != null || uom != null || description != null || unitPrice != null || productId != null; }
  }
  // OP-CAP-09D: bounded draft review queue summary (no full line arrays, no raw AI/document/message payloads).
  public record DraftReviewSummary(UUID draftId, String draftType, String status, UUID sourceReviewCaseId, UUID sourceValidationRunId, UUID customerAccountId, String customerName, int lineCount, BigDecimal subtotalAmount, BigDecimal totalAmount, String currency, Instant createdAt, Instant updatedAt, String externalExecution, String nextAction) {}
  // OP-CAP-09D: read-only product picker item. No cost / margin / supplier / inventory-private fields.
  public record ProductPickerItem(UUID productId, String sku, String name, String normalizedSku, String status) {}

  // Wave 01A — safe response DTOs for workspace controllers (no tenant/actor/audit/source internal leaks).

  public record WorkspaceNoteDto(UUID id, String targetType, UUID targetId, String noteText, Instant createdAt) {}

  public record WorkspaceDraftQuoteLineDto(
      UUID id, int lineNumber, UUID productId, String productName, String description,
      BigDecimal quantity, String uom, BigDecimal unitPrice, BigDecimal discountPercent,
      BigDecimal lineTotal, BigDecimal marginPercent, String status, String validationStatus) {}

  public record WorkspaceDraftQuoteDto(
      UUID id, String quoteNumber, UUID customerAccountId, String customerDisplayName,
      String status, String validationStatus, boolean requiresHumanReview,
      String currency, BigDecimal subtotalAmount, BigDecimal discountAmount,
      BigDecimal totalAmount, BigDecimal marginPercent, Instant createdAt) {}

  public record WorkspaceDraftOrderLineDto(
      UUID id, int lineNumber, UUID productId, String description,
      BigDecimal quantity, String uom, BigDecimal unitPrice, BigDecimal discountPercent,
      BigDecimal lineTotal, BigDecimal marginPercent, String status, String validationStatus) {}

  public record WorkspaceDraftOrderDto(
      UUID id, String orderNumber, UUID customerAccountId,
      String status, String currency, BigDecimal subtotalAmount, BigDecimal discountAmount,
      BigDecimal totalAmount, BigDecimal marginPercent, Instant createdAt) {}

  // Wave 01B — bounded workspace response contracts. Internal source, tenant, actor and raw JSON
  // fields remain on domain entities only and are never part of the public controller shape.
  public record ExceptionCaseDto(
      UUID id, String caseNumber, String title, String status, String priority, String severity,
      String summary, Instant createdAt, Instant resolvedAt) {}

  public record ExceptionCaseIssueDto(
      UUID id, String issueType, String severity, String status, String message) {}

  public record SuggestedFixDto(
      UUID id, String fixType, String status, BigDecimal confidence, String reason) {}

  public record ApprovalDecisionDto(
      UUID id, String targetType, UUID targetId, String decision, String reason, Instant decidedAt) {}
}
