package com.orderpilot.application.services.extraction;

import com.orderpilot.api.dto.Stage4Dtos.ExtractionRunRequest;
import com.orderpilot.api.dto.Stage4Dtos.ExtractionSubmissionResponse;
import com.orderpilot.application.services.AuditEventService;
import com.orderpilot.application.services.ProcessingJobService;
import com.orderpilot.application.services.runtime.AiWorkloadClassificationRequest;
import com.orderpilot.application.services.runtime.AiWorkloadType;
import com.orderpilot.application.services.runtime.RuntimeControlDecision;
import com.orderpilot.application.services.runtime.RuntimeControlRequest;
import com.orderpilot.application.services.runtime.RuntimeControlService;
import com.orderpilot.application.services.runtime.RuntimeFeatureType;
import com.orderpilot.application.services.runtime.RuntimeGuardReasonCodes;
import com.orderpilot.application.services.runtime.RuntimeOperationType;
import com.orderpilot.application.services.runtime.RuntimeLimitException;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimateRequest;
import com.orderpilot.application.services.runtime.RuntimeUnitEstimator;
import com.orderpilot.application.services.runtime.UsageMeterService;
import com.orderpilot.common.tenant.TenantContext;
import com.orderpilot.domain.extraction.*;
import com.orderpilot.domain.intake.InboundDocumentRepository;
import com.orderpilot.domain.intake.ProcessingJob;
import com.orderpilot.domain.usage.UsageSource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionPipelineService implements AiUnderstandingPipeline {
  private final ExtractionRunService runService; private final TextExtractionService textService; private final SemanticExtractionService semanticService; private final AuditEventService auditEventService; private final SemanticExtractionProvider provider; private final RuntimeControlService runtimeControlService; private final ProcessingJobService processingJobService; private final RuntimeUnitEstimator runtimeUnitEstimator; private final InboundDocumentRepository inboundDocumentRepository; private final UsageMeterService usageMeterService; private final Clock clock;
  public ExtractionPipelineService(ExtractionRunService runService, TextExtractionService textService, SemanticExtractionService semanticService, AuditEventService auditEventService, SemanticExtractionProvider provider, RuntimeControlService runtimeControlService, ProcessingJobService processingJobService, RuntimeUnitEstimator runtimeUnitEstimator, InboundDocumentRepository inboundDocumentRepository, UsageMeterService usageMeterService, Clock clock){this.runService=runService; this.textService=textService; this.semanticService=semanticService; this.auditEventService=auditEventService; this.provider=provider; this.runtimeControlService=runtimeControlService; this.processingJobService=processingJobService; this.runtimeUnitEstimator=runtimeUnitEstimator; this.inboundDocumentRepository=inboundDocumentRepository; this.usageMeterService=usageMeterService; this.clock=clock;}

  /**
   * OP-CAP-27c — the async document-extraction submission boundary. Runs the OP-CAP-27 runtime-control
   * admission gate (entitlement -&gt; quota -&gt; rate; a denial throws the existing stable mapped
   * exception BEFORE any enqueue) and, when admitted, enqueues durable work onto the existing
   * {@link ProcessingJobService} runtime for the out-of-process worker to process. It performs NO
   * text/OCR/semantic extraction in the request thread and creates NO {@link ExtractionRun} — the heavy
   * work happens later via the worker (which returns results through the AI-worker result intake) or the
   * internal executor {@link #runNow}. A non-allowed decision (needs-review / unsupported / duplicate)
   * fail-closes without enqueueing. Tenant is resolved server-side; the actor is the trusted system/job
   * actor; the workload is fixed to {@code DOCUMENT_EXTRACTION}. The request transaction only creates a
   * job + audit row — never OCR/semantic work. {@code enqueue} is idempotent per (tenant, target,
   * PENDING), so a re-submission returns the existing job rather than creating a second one.
   */
  @Transactional public ExtractionSubmissionResponse submitForExtraction(ExtractionRunRequest request) {
    UUID tenantId = TenantContext.requireTenantId();
    if (request == null || request.sourceType() == null || request.sourceId() == null) {
      throw new IllegalArgumentException("sourceType and sourceId are required");
    }
    Long knownSizeBytes = knownDocumentSizeBytes(tenantId, request);
    int requestedUnits =
        runtimeUnitEstimator.estimate(
            RuntimeUnitEstimateRequest.forDocumentExtraction(tenantId, null, knownSizeBytes, null));
    RuntimeControlRequest controlRequest = extractionControlRequest(tenantId, requestedUnits);
    RuntimeControlDecision decision = enforceWithEvidence(controlRequest, request);
    if (!decision.allowed()) {
      // needs-review / unsupported / duplicate: enqueue nothing and do no work. Safe message only.
      recordDecisionEvidence(controlRequest, request, decision, false);
      throw new IllegalArgumentException(decision.safeMessage());
    }
    ProcessingJob job = processingJobService.enqueue(
        tenantId, "DOCUMENT_EXTRACTION", request.sourceType(), request.sourceId());
    recordAccepted(controlRequest, request, decision);
    auditEventService.record("extraction.submitted_async", "processing_job", job.getId().toString(), null,
        "{\"sourceType\":\"" + request.sourceType() + "\",\"async\":true,\"advisoryOnly\":true}");
    return new ExtractionSubmissionResponse(job.getId(), job.getJobType(), job.getTargetType(),
        job.getTargetId(), job.getStatus(), true, true, "Accepted for asynchronous extraction.");
  }

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
    // OP-CAP-27b: route document-extraction admission through the centralized runtime-control decision
    // spine (classification + entitlement -> quota -> rate) instead of calling the guard directly. A
    // guard denial still throws the existing stable mapped exception (403 feature/quota, 429 rate)
    // BEFORE any run/extraction work, and a non-allowed decision (unsupported / needs-review /
    // duplicate) fail-closes without creating a run or invoking a provider. Tenant is resolved
    // server-side; actorId is null here because this is a trusted internal/system pipeline call. The
    // estimator-derived requestedUnits are passed through so quota math is unchanged. No raw document
    // content is read for the decision.
    RuntimeControlRequest controlRequest = extractionControlRequest(tenantId, requestedUnits);
    RuntimeControlDecision decision = enforceWithEvidence(controlRequest, request);
    if (!decision.allowed()) {
      // Unsupported / needs-review / duplicate: do no work and create no run. Safe message only.
      recordDecisionEvidence(controlRequest, request, decision, false);
      throw new IllegalArgumentException(decision.safeMessage());
    }

    ExtractionRun run = runService.create(request, provider.providerName(), provider.schemaVersion());
    recordAccepted(controlRequest, request, decision);
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
   * OP-CAP-27b: the runtime-control workload descriptor for an extraction submission. Pre-extraction we
   * do not yet have parsed text or page count (those exist only after parse/OCR), so the submission is
   * represented as a single supported {@code DOCUMENT_EXTRACTION} document — a real, non-empty,
   * heavy-class workload. No raw content is read here; admission is still gated by entitlement → quota →
   * rate via the runtime-control decision. Quota magnitude continues to come from the size-aware unit
   * estimator (passed as {@code requestedUnits}), not from this descriptor.
   */
  private static AiWorkloadClassificationRequest documentExtractionWorkload() {
    return new AiWorkloadClassificationRequest(AiWorkloadType.DOCUMENT_EXTRACTION, null, 0, 1, false, false);
  }

  private static RuntimeControlRequest extractionControlRequest(UUID tenantId, int requestedUnits) {
    return new RuntimeControlRequest(
        tenantId, null, RuntimeOperationType.AI_DOCUMENT_EXTRACTION,
        RuntimeFeatureType.AI_DOCUMENT_EXTRACTION, documentExtractionWorkload(),
        requestedUnits, null, false);
  }

  private RuntimeControlDecision enforceWithEvidence(
      RuntimeControlRequest controlRequest, ExtractionRunRequest request) {
    try {
      return runtimeControlService.enforce(controlRequest);
    } catch (RuntimeLimitException ex) {
      recordDecisionEvidence(controlRequest, request, fromLimitException(controlRequest, ex), false);
      throw ex;
    }
  }

  private void recordAccepted(
      RuntimeControlRequest controlRequest, ExtractionRunRequest request, RuntimeControlDecision decision) {
    usageMeterService.recordRuntimeControlDecision(
        controlRequest, decision, UsageSource.EXTRACTION_PIPELINE, sourceRef(request),
        acceptedMeteringKey(request), true);
  }

  private void recordDecisionEvidence(
      RuntimeControlRequest controlRequest,
      ExtractionRunRequest request,
      RuntimeControlDecision decision,
      boolean downstreamInvoked) {
    usageMeterService.recordRuntimeControlDecisionEvidence(
        controlRequest, decision, UsageSource.EXTRACTION_PIPELINE, sourceRef(request), null,
        downstreamInvoked);
  }

  private static RuntimeControlDecision fromLimitException(
      RuntimeControlRequest request, RuntimeLimitException ex) {
    var guard = ex.getDecision();
    String reason = guard == null ? "runtime_control_denied" : guard.reasonCode();
    var outcome = com.orderpilot.application.services.runtime.RuntimeControlOutcome.QUOTA_EXCEEDED;
    if (RuntimeGuardReasonCodes.RATE_LIMIT_EXCEEDED.equals(reason)) {
      outcome = com.orderpilot.application.services.runtime.RuntimeControlOutcome.RATE_LIMITED;
    } else if (RuntimeGuardReasonCodes.isFeatureDenial(reason)) {
      outcome = com.orderpilot.application.services.runtime.RuntimeControlOutcome.DISABLED;
    }
    return new RuntimeControlDecision(
        outcome, reason, AiWorkloadType.DOCUMENT_EXTRACTION,
        com.orderpilot.application.services.runtime.ModelTier.NONE, request.tenantId(), request.actorId(),
        request.actorId() == null, request.idempotencyKey(), false, false, false, false,
        (int) Math.min(Integer.MAX_VALUE, Math.max(0L, request.requestedUnits())),
        ex.getHttpStatus(), ex.getRetryAfterSeconds(), ex.getMessage());
  }

  private static String sourceRef(ExtractionRunRequest request) {
    return request == null || request.sourceId() == null
        ? "DOCUMENT_EXTRACTION"
        : "DOCUMENT_EXTRACTION:" + request.sourceId();
  }

  private static String acceptedMeteringKey(ExtractionRunRequest request) {
    return request == null || request.sourceId() == null
        ? null
        : "runtime-control:document-extraction:" + request.sourceId();
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
