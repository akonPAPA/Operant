package com.orderpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Stage4Dtos {
  private Stage4Dtos() {}
  public record ExtractionRunRequest(String sourceType, UUID sourceId, UUID processingJobId, String providerType) {}
  public record ExtractionRunResponse(UUID id, String sourceType, UUID sourceId, UUID processingJobId, String status, String providerType, String providerName, String schemaVersion, Instant createdAt) {}
  // OP-CAP-27c: safe async-submission acknowledgement returned by the document-extraction submission
  // boundary. Carries only the durable ProcessingJob handle/status and a safe message — never raw
  // extracted text, prompts, provider payloads, quota internals, or cross-tenant identifiers.
  public record ExtractionSubmissionResponse(UUID jobId, String jobType, String targetType, UUID targetId, String status, boolean accepted, boolean async, String message) {}
  public record ExtractionResultResponse(UUID id, UUID extractionRunId, String detectedIntent, String documentType, BigDecimal overallConfidence, String validationStatus, String resultJson) {}
  public record ExtractedFieldResponse(UUID id, String fieldName, String rawValue, BigDecimal confidence, String validationStatus) {}
  public record ExtractedLineItemResponse(UUID id, int lineNumber, String rawSku, String rawDescription, BigDecimal confidence, String validationStatus) {}
  public record SourceEvidenceResponse(UUID id, String evidenceType, String snippet) {}
  public record AiSuggestionResponse(UUID id, String suggestionType, String suggestionJson, BigDecimal confidence, String status) {}
}