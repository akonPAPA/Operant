"""Job handler: scoped job request -> 07B pipeline -> controlled advisory result (OP-CAP-07C).

Behaviour:

    1. validate job envelope (fail closed)        -> REJECTED on malformed/unsafe/too-large input
    2. resolve provider for requested pipeline     -> deterministic rule-based by default
    3. run the 07B extraction pipeline             -> advisory ExtractionResult only
    4. map extraction outcome to a job status      -> SUCCEEDED / NEEDS_REVIEW / FAILED
    5. emit one bounded structured log line         -> no raw content, no secrets
    6. return a schema-valid AiProcessingJobResult  -> caller may hand it off via an AiResultSink

There is no business validation, catalog/inventory/price/customer lookup, approval, or mutation path
anywhere in this flow. The worker only reads text and emits advisory structure.
"""

import logging
import time
from datetime import datetime, timezone

from orderpilot_ai_worker.extraction.pipeline import ExtractionInput, SemanticExtractionPipeline
from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult
from orderpilot_ai_worker.jobs.models import (
    AiJobSourceType,
    AiJobStatus,
    AiProcessingJobRequest,
    AiProcessingJobResult,
    ProviderMetadata,
)
from orderpilot_ai_worker.jobs.provider_factory import (
    ProviderResolutionError,
    build_extraction_provider,
)
from orderpilot_ai_worker.jobs.provider_factory import _SIMPLE_PROVIDERS as _PROVIDERS
from orderpilot_ai_worker.jobs.security import JobRejected, validate_job_envelope

_LOGGER = logging.getLogger("orderpilot.ai_worker.jobs")

# A result is confident enough to be SUCCEEDED only at/above this bar. Below it the job is routed to
# human review via Core API. Mirrors the pipeline's own ready-for-validation threshold.
_SUCCESS_CONFIDENCE_FLOOR = 0.5

# Map the job source type to the 07B pipeline's source_type vocabulary. UNKNOWN is intentionally left
# unmapped so the pipeline tags it ``unsupported_source_type`` and treats it as low-trust content.
_SOURCE_TYPE_TO_PIPELINE = {
    AiJobSourceType.CHANNEL_MESSAGE: "channel_message",
    AiJobSourceType.INBOUND_DOCUMENT: "inbound_document",
    AiJobSourceType.EMAIL_BODY: "email",
    AiJobSourceType.PDF_TEXT: "pdf_text",
    AiJobSourceType.EXCEL_TEXT: "excel",
    AiJobSourceType.CSV_TEXT: "csv",
    AiJobSourceType.API_UPLOAD_TEXT: "api",
    AiJobSourceType.UNKNOWN: "unknown",
}

# ``_PROVIDERS`` is the same dict the provider factory resolves the deterministic (no-arg) modes from
# re-exported here so existing job tests can monkeypatch a single mode in place. Provider selection
# (including the local Ollama runtime, which needs config + transport) goes through
# ``build_extraction_provider``; see ``jobs.provider_factory``.


def process_ai_extraction_job(request: AiProcessingJobRequest) -> AiProcessingJobResult:
    """Process one scoped advisory extraction job and return a controlled, schema-valid result."""
    started_at = datetime.now(timezone.utc)
    started_perf = time.perf_counter()

    try:
        safe_text = validate_job_envelope(request)
    except JobRejected as rejected:
        return _finalize(
            _base_result(request, AiJobStatus.REJECTED, started_at),
            started_perf,
            warnings=[],
            safe_failure_reason=rejected.reason,
        )

    # Resolve the requested provider mode to a constructed advisory provider. The envelope check above
    # already rejects modes the worker cannot run; this is defense-in-depth and fails closed safely if
    # a mode is somehow unresolvable (e.g. a future placeholder), without reaching any network.
    try:
        resolved = build_extraction_provider(request.requested_pipeline)
    except ProviderResolutionError as unresolved:
        result = _base_result(request, AiJobStatus.FAILED, started_at)
        return _finalize(result, started_perf, warnings=[], safe_failure_reason=unresolved.reason)

    pipeline = SemanticExtractionPipeline(provider=resolved.provider)

    pipeline_input = ExtractionInput(
        source_type=_SOURCE_TYPE_TO_PIPELINE[request.source_type],
        source_id=request.source_id,
        raw_text=safe_text,
        tenant_id=request.tenant_ref,
        source_metadata=_pipeline_metadata(request),
    )

    # The pipeline already fails closed internally (provider error / invalid output -> failed result),
    # so a raised exception here is unexpected; treat it as a controlled FAILED, never a leak.
    try:
        extraction = pipeline.run(pipeline_input)
    except Exception:  # noqa: BLE001 - never surface internals/customer text to the caller
        result = _base_result(request, AiJobStatus.FAILED, started_at)
        return _finalize(result, started_perf, warnings=[], safe_failure_reason="pipeline_error")

    status, failure_reason = _status_for(extraction)
    result = _base_result(request, status, started_at)
    result.extraction_result = extraction
    result.prompt_injection_signals = list(extraction.prompt_injection_signals)
    result.provider_metadata = ProviderMetadata(
        provider_name=resolved.provider_name,
        provider_version=resolved.provider_version,
        schema_version=extraction.schema_version,
        mode=request.requested_pipeline,
    )
    return _finalize(
        result,
        started_perf,
        warnings=list(extraction.warnings),
        safe_failure_reason=failure_reason,
    )


def _status_for(extraction: ExtractionResult) -> tuple[AiJobStatus, str | None]:
    """Map an advisory extraction outcome to a controlled job status + optional safe reason."""
    if extraction.validation_status == "failed":
        # Controlled pipeline failure (e.g. provider_error / invalid_provider_output). The reason is
        # already a bounded, safe token in the extraction warnings.
        reason = extraction.warnings[0] if extraction.warnings else "extraction_failed"
        return AiJobStatus.FAILED, reason
    if extraction.prompt_injection_signals:
        return AiJobStatus.NEEDS_REVIEW, None
    if extraction.validation_status == "needs_review":
        return AiJobStatus.NEEDS_REVIEW, None
    if extraction.overall_confidence < _SUCCESS_CONFIDENCE_FLOOR:
        return AiJobStatus.NEEDS_REVIEW, None
    return AiJobStatus.SUCCEEDED, None


def _base_result(
    request: AiProcessingJobRequest, status: AiJobStatus, started_at: datetime
) -> AiProcessingJobResult:
    return AiProcessingJobResult(
        job_id=request.job_id,
        tenant_ref=request.tenant_ref,
        source_type=request.source_type,
        source_id=request.source_id,
        status=status,
        started_at=started_at,
    )


def _finalize(
    result: AiProcessingJobResult,
    started_perf: float,
    warnings: list[str],
    safe_failure_reason: str | None,
) -> AiProcessingJobResult:
    result.warnings = warnings
    result.safe_failure_reason = safe_failure_reason
    result.completed_at = datetime.now(timezone.utc)
    result.duration_ms = max(0, int((time.perf_counter() - started_perf) * 1000))
    _log_outcome(result)
    return result


def _pipeline_metadata(request: AiProcessingJobRequest) -> dict:
    metadata = dict(request.source_metadata)
    if request.source_channel and "source_channel" not in metadata:
        metadata["source_channel"] = request.source_channel
    return metadata


def _log_outcome(result: AiProcessingJobResult) -> None:
    """Emit one bounded structured log line. No raw content, no secrets, no customer payload."""
    _LOGGER.info(
        "ai_worker_job_processed",
        extra={
            "job_id": result.job_id,
            "source_type": result.source_type.value,
            "source_id": result.source_id,
            "status": result.status.value,
            "duration_ms": result.duration_ms,
            "warning_count": len(result.warnings),
            "prompt_injection_signal_count": len(result.prompt_injection_signals),
        },
    )
