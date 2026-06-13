package com.orderpilot.application.services.extraction;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardRequest;
import com.orderpilot.application.services.runtime.RuntimeGuardService;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimateRequest;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionPipelineService implements AiUnderstandingPipeline {
  private final ExtractionRunService runService; private final TextExtractionService textService; private final SemanticExtractionService semanticService; private final AuditEventService auditEventService; private final SemanticExtractionProvider provider; private final RuntimeGuardService runtimeGuardService; private final RuntimeUnitEstimator runtimeUnitEstimator; private final InboundDocumentRepository inboundDocumentRepository; private final Clock clock;
  public ExtractionPipelineService(ExtractionRunService runService, TextExtractionService textService, SemanticExtractionService semanticService, AuditEventService auditEventService, SemanticExtractionProvider provider, RuntimeGuardService runtimeGuardService, RuntimeUnitEstimator runtimeUnitEstimator, InboundDocumentRepository inboundDocumentRepository, Clock clock){this.runService=runService; this.textService=textService; this.semanticService=semanticService; this.auditEventService=auditEventService; this.provider=provider; this.runtimeGuardService=runtimeGuardService; this.runtimeUnitEstimator=runtimeUnitEstimator; this.inboundDocumentRepository=inboundDocumentRepository; this.clock=clock;}
  @Override
  @Transactional public ExtractionRun runNow(ExtractionRunRequest request) {
    // OP-CAP-16D/16F runtime guard: entitlement -> quota -> rate, BEFORE any run/job creation or
    // text/semantic extraction work. A denial throws a stable mapped exception (403 feature/quota,
    // 429 rate) and leaves no ExtractionRun record and triggers no extraction provider call.
    // OP-CAP-16F/16G: requested units come from the estimator. For an INBOUND_DOCUMENT source we
    // cheaply thread the already-stored file size (a tenant-scoped primary-key lookup, indexed; no
    // file open, no parse, no OCR, no object-storage read) so the estimate can exceed 1. Page count
    // is not known pre-extraction (it only becomes available after parse/OCR) so it is not used here.
    // For non-document sources (e.g. CHANNEL_MESSAGE) no cheap size metadata exists and the estimator
    // falls back to 1 — documented in the STAGE_16G doc.
    UUID tenantId = TenantContext.requireTenantId();
    Long knownSizeBytes = knownDocumentSizeBytes(tenantId, request);
    int requestedUnits =
        runtimeUnitEstimator.estimate(
            RuntimeUnitEstimateRequest.forDocumentExtraction(tenantId, null, knownSizeBytes, null));
    runtimeGuardService.enforce(
        RuntimeGuardRequest.of(
            tenantId, RuntimeOperationType.AI_DOCUMENT_EXTRACTION, requestedUnits),
        RuntimeFeatureType.AI_DOCUMENT_EXTRACTION);

    ExtractionRun run = runService.create(request, provider.providerName(), provider.schemaVersion());
    try {
      run.markRunning(clock.instant());
      ExtractedDocumentText text = textService.extractAndStore(run);
      ExtractionResult result = semanticService.extractAndStore(run, text);
      if ("NEEDS_REVIEW".equals(result.getValidationStatus())) {
        run.markNeedsReview(clock.instant());
        auditEventService.record("extraction_run.needs_review", "extraction_run", run.getId().toString(), null, "{\"stage\":\"4\",\"advisoryOnly\":true}");
      } else {
        run.markSucceeded(clock.instant());
        auditEventService.record("extraction_run.succeeded", "extraction_run", run.getId().toString(), null, "{\"stage\":\"4\",\"advisoryOnly\":true}");
      }
      return run;
    } catch (RuntimeException ex) {
      run.markFailed(ex.getMessage(), clock.instant());
      auditEventService.record("extraction_run.failed", "extraction_run", run.getId().toString(), null, "{\"stage\":\"4\"}");
      throw ex;
    }
  }

  /**
   * OP-CAP-16G: cheap, tenant-scoped pre-extraction size metadata. Only the already-persisted
   * {@code file_size_bytes} of an INBOUND_DOCUMENT is used, fetched by primary key within the tenant
   * scope. Returns {@code null} for any other source type, a missing/cross-tenant id, or a document
   * with no recorded size — in which case the estimator falls back to 1. No file is opened.
   */
  private Long knownDocumentSizeBytes(UUID tenantId, ExtractionRunRequest request) {
    if (request == null || request.sourceId() == null
        || !"INBOUND_DOCUMENT".equals(request.sourceType())) {
      return null;
    }
    return inboundDocumentRepository
        .findByIdAndTenantId(request.sourceId(), tenantId)
        .map(com.orderpilot.domain.intake.InboundDocument::getFileSizeBytes)
        .orElse(null);
  }
}
