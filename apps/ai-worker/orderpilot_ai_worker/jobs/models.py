"""Job request/result contracts for the AI-worker integration foundation (OP-CAP-07C).

These models are the wire contract between Core API and the advisory worker. They carry tenant/source
correlation only — Core API remains the tenant *authority*. They deliberately contain no DB
credentials, no secrets, no unrestricted tenant/customer data, and no executable action/mutation
field. Malformed input fails closed (see ``orderpilot_ai_worker.jobs.security``).
"""

from datetime import datetime, timezone
from enum import Enum
from typing import List, Optional

from pydantic import BaseModel, Field

from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult

# Schema version for the 07C job envelope/result contract. Distinct from the 07B extraction schema
# version (``stage4.v1``) carried inside ``ExtractionResult``.
JOB_SCHEMA_VERSION = "op-cap-07c.v1"


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class AiJobSourceType(str, Enum):
    """Where the inbound text originated. Carried for correlation/routing, never trusted as auth."""

    CHANNEL_MESSAGE = "CHANNEL_MESSAGE"
    INBOUND_DOCUMENT = "INBOUND_DOCUMENT"
    EMAIL_BODY = "EMAIL_BODY"
    PDF_TEXT = "PDF_TEXT"
    EXCEL_TEXT = "EXCEL_TEXT"
    CSV_TEXT = "CSV_TEXT"
    API_UPLOAD_TEXT = "API_UPLOAD_TEXT"
    UNKNOWN = "UNKNOWN"


class AiJobStatus(str, Enum):
    """Controlled terminal status of a processing job."""

    SUCCEEDED = "SUCCEEDED"  # schema-valid advisory extraction exists and is confident enough
    NEEDS_REVIEW = "NEEDS_REVIEW"  # extraction exists but low confidence or injection signals
    FAILED = "FAILED"  # worker could not produce a valid result
    REJECTED = "REJECTED"  # input malformed/unsafe/too-large/unsupported per worker-local checks


class ProviderMode(str, Enum):
    """Extraction provider modes. Default deterministic; no real LLM call in this PR."""

    RULE_BASED = "RULE_BASED"
    MOCK_SEMANTIC = "MOCK_SEMANTIC"
    FUTURE_SEMANTIC = "FUTURE_SEMANTIC"  # placeholder only — not available, fails closed


class JobSecurityContext(BaseModel):  # pylint: disable=too-few-public-methods
    """Minimal, non-secret transport/auth context for a job.

    This is intentionally *not* security theater and intentionally *not* custom crypto. Real transport
    security is HTTPS/TLS and a service token verified by Core API. The optional ``signature`` /
    ``nonce`` / ``issued_at`` fields are placeholders for a future standard-HMAC signed-request /
    replay-resistance scheme; they are never required and never populated with secrets here.
    """

    service_id: Optional[str] = None
    issued_at: Optional[datetime] = None
    nonce: Optional[str] = None
    signature: Optional[str] = None


class AiProcessingJobRequest(BaseModel):  # pylint: disable=too-few-public-methods
    """Scoped input for an advisory AI processing job.

    ``raw_text`` is the only content this PR processes. ``text_ref`` / ``object_storage_ref`` are
    accepted as references only — the worker does NOT fetch external URLs/storage in this PR, so a
    request carrying only a reference (no inline text) fails closed as REJECTED.
    """

    job_id: str
    tenant_ref: str
    source_type: AiJobSourceType
    source_id: str
    source_channel: Optional[str] = None
    content_type: Optional[str] = None
    raw_text: Optional[str] = None
    text_ref: Optional[str] = None
    object_storage_ref: Optional[str] = None
    source_metadata: dict = Field(default_factory=dict)
    requested_pipeline: ProviderMode = ProviderMode.RULE_BASED
    schema_version: str = JOB_SCHEMA_VERSION
    created_at: datetime = Field(default_factory=_utcnow)
    security_context: Optional[JobSecurityContext] = None


class ProviderMetadata(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory provenance for the extraction provider that produced a result."""

    provider_name: str
    provider_version: str
    schema_version: str
    mode: ProviderMode


class AiProcessingJobResult(BaseModel):  # pylint: disable=too-few-public-methods
    """Controlled, schema-valid, JSON-serializable advisory result of a processing job.

    Hard boundary: this carries an advisory ``ExtractionResult`` (or none on failure) plus bounded
    metadata. It never carries stack traces, secrets, executable commands, raw prompt-injection
    payload, or any field that says "approve quote" / "update stock" / "create order" / "write ERP".
    """

    job_id: str
    tenant_ref: str
    source_type: AiJobSourceType
    source_id: str
    status: AiJobStatus
    extraction_result: Optional[ExtractionResult] = None
    warnings: List[str] = Field(default_factory=list)
    errors: List[str] = Field(default_factory=list)
    prompt_injection_signals: List[str] = Field(default_factory=list)
    provider_metadata: Optional[ProviderMetadata] = None
    schema_version: str = JOB_SCHEMA_VERSION
    started_at: datetime = Field(default_factory=_utcnow)
    completed_at: Optional[datetime] = None
    duration_ms: Optional[int] = None
    safe_failure_reason: Optional[str] = None
