"""AI-worker job integration + secure Core handoff foundation (OP-CAP-07C).

This package wraps the OP-CAP-07B advisory understanding pipeline with the orchestration the rest
of OrderPilot needs to actually *use* the worker, without ever letting it become a business actor:

    Core API / channel / document
      -> scoped AI processing job request
      -> job-envelope security validation (bounds / allowed types / fail-closed)
      -> 07B extraction pipeline (deterministic, advisory only)
      -> schema-valid advisory result + controlled status
      -> secure handoff sink back to Core API

07B is the extraction engine. 07C is the job/handoff/security shell around it. Nothing here can
write orders/quotes/inventory/catalog/customer/price data, approve anything, or trigger ERP/connector
writes — the result schema has no action/mutation surface and there is no DB/ERP client in the worker.
"""

from orderpilot_ai_worker.jobs.handler import process_ai_extraction_job
from orderpilot_ai_worker.jobs.handoff import (
    AiResultSink,
    InMemoryResultSink,
    PublishOutcome,
)
from orderpilot_ai_worker.jobs.models import (
    JOB_SCHEMA_VERSION,
    AiJobSourceType,
    AiJobStatus,
    AiProcessingJobRequest,
    AiProcessingJobResult,
    JobSecurityContext,
    ProviderMetadata,
    ProviderMode,
)

__all__ = [
    "process_ai_extraction_job",
    "AiResultSink",
    "InMemoryResultSink",
    "PublishOutcome",
    "JOB_SCHEMA_VERSION",
    "AiJobSourceType",
    "AiJobStatus",
    "AiProcessingJobRequest",
    "AiProcessingJobResult",
    "JobSecurityContext",
    "ProviderMetadata",
    "ProviderMode",
]
