package com.orderpilot.api.rest;

import com.orderpilot.api.dto.Stage4Dtos.*;
import com.orderpilot.application.services.extraction.*;
import com.orderpilot.domain.extraction.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/extractions")
public class ExtractionController {
  private final ExtractionPipelineService pipelineService;
  private final ExtractionRunService runService;
  private final ExtractionReviewService reviewService;
  private final SemanticExtractionProvider provider;

  public ExtractionController(ExtractionPipelineService pipelineService, ExtractionRunService runService, ExtractionReviewService reviewService, SemanticExtractionProvider provider) {
    this.pipelineService = pipelineService;
    this.runService = runService;
    this.reviewService = reviewService;
    this.provider = provider;
  }

  @PostMapping("/runs")
  public ExtractionRunResponse createRun(@RequestBody ExtractionRunRequest request) {
    return toRun(runService.create(request, provider.providerName(), provider.schemaVersion()));
  }

  // OP-CAP-27c: heavy document extraction is no longer executed synchronously in the request thread.
  // This endpoint routes through the runtime-control admission gate and enqueues durable async work on
  // the existing ProcessingJob runtime, returning a safe accepted/job-status acknowledgement.
  @PostMapping("/runs/execute")
  public ExtractionSubmissionResponse executeRun(@RequestBody ExtractionRunRequest request) {
    return pipelineService.submitForExtraction(request);
  }

  @GetMapping("/runs")
  public List<ExtractionRunResponse> runs() {
    return runService.list().stream().map(this::toRun).toList();
  }

  @GetMapping("/runs/{id}")
  public ExtractionRunResponse run(@PathVariable UUID id) {
    return toRun(runService.get(id));
  }

  @GetMapping("/results")
  public List<ExtractionResultResponse> results() {
    return reviewService.results().stream().map(this::toResult).toList();
  }

  @GetMapping("/results/{id}")
  public ExtractionResultResponse result(@PathVariable UUID id) {
    return toResult(reviewService.result(id));
  }

  @GetMapping("/sources/{sourceType}/{sourceId}/results")
  public List<ExtractionResultResponse> resultsForSource(@PathVariable String sourceType, @PathVariable UUID sourceId) {
    return reviewService.resultsForSource(sourceType, sourceId).stream().map(this::toResult).toList();
  }

  @GetMapping("/runs/{id}/result")
  public ExtractionResultResponse resultForRun(@PathVariable UUID id) {
    return toResult(reviewService.resultForRun(id));
  }

  @GetMapping("/runs/{id}/fields")
  public List<ExtractedFieldResponse> fields(@PathVariable UUID id) {
    return reviewService.fields(reviewService.resultForRun(id).getId()).stream().map(this::toField).toList();
  }

  @GetMapping("/runs/{id}/line-items")
  public List<ExtractedLineItemResponse> lineItems(@PathVariable UUID id) {
    return reviewService.lineItems(reviewService.resultForRun(id).getId()).stream().map(this::toLine).toList();
  }

  @GetMapping("/runs/{id}/evidence")
  public List<SourceEvidenceResponse> evidence(@PathVariable UUID id) {
    return reviewService.evidence(id).stream().map(this::toEvidence).toList();
  }

  @GetMapping("/runs/{id}/suggestions")
  public List<AiSuggestionResponse> suggestions(@PathVariable UUID id) {
    return reviewService.suggestions(id).stream().map(this::toSuggestion).toList();
  }

  @PostMapping("/fields/{id}/mark-needs-review")
  public ExtractedFieldResponse fieldNeedsReview(@PathVariable UUID id) {
    return toField(reviewService.markField(id, "NEEDS_REVIEW"));
  }

  @PostMapping("/fields/{id}/reject")
  public ExtractedFieldResponse fieldReject(@PathVariable UUID id) {
    return toField(reviewService.markField(id, "REJECTED"));
  }

  @PostMapping("/fields/{id}/accept-for-validation")
  public ExtractedFieldResponse fieldAccept(@PathVariable UUID id) {
    return toField(reviewService.markField(id, "ACCEPTED_FOR_VALIDATION"));
  }

  @PostMapping("/line-items/{id}/mark-needs-review")
  public ExtractedLineItemResponse lineNeedsReview(@PathVariable UUID id) {
    return toLine(reviewService.markLine(id, "NEEDS_REVIEW"));
  }

  @PostMapping("/line-items/{id}/reject")
  public ExtractedLineItemResponse lineReject(@PathVariable UUID id) {
    return toLine(reviewService.markLine(id, "REJECTED"));
  }

  @PostMapping("/line-items/{id}/accept-for-validation")
  public ExtractedLineItemResponse lineAccept(@PathVariable UUID id) {
    return toLine(reviewService.markLine(id, "ACCEPTED_FOR_VALIDATION"));
  }

  private ExtractionRunResponse toRun(ExtractionRun run) {
    return new ExtractionRunResponse(run.getId(), run.getSourceType(), run.getSourceId(), run.getProcessingJobId(), run.getStatus(), run.getProviderType(), run.getProviderName(), run.getSchemaVersion(), run.getCreatedAt());
  }

  private ExtractionResultResponse toResult(ExtractionResult result) {
    return new ExtractionResultResponse(result.getId(), result.getExtractionRunId(), result.getDetectedIntent(), result.getDocumentType(), result.getOverallConfidence(), result.getValidationStatus(), result.getResultJson());
  }

  private ExtractedFieldResponse toField(ExtractedField field) {
    return new ExtractedFieldResponse(field.getId(), field.getFieldName(), field.getRawValue(), field.getConfidence(), field.getValidationStatus());
  }

  private ExtractedLineItemResponse toLine(ExtractedLineItem line) {
    return new ExtractedLineItemResponse(line.getId(), line.getLineNumber(), line.getRawSku(), line.getRawDescription(), line.getConfidence(), line.getValidationStatus());
  }

  private SourceEvidenceResponse toEvidence(SourceEvidence evidence) {
    return new SourceEvidenceResponse(evidence.getId(), evidence.getEvidenceType(), evidence.getSnippet());
  }

  private AiSuggestionResponse toSuggestion(AiSuggestion suggestion) {
    return new AiSuggestionResponse(suggestion.getId(), suggestion.getSuggestionType(), suggestion.getSuggestionJson(), suggestion.getConfidence(), suggestion.getStatus());
  }
}
