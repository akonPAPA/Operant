"""Security envelope for AI-worker job handling (OP-CAP-07C).

Fail-closed input controls applied *before* the extraction pipeline runs, plus the small set of
output/transport guarantees the worker promises. There is no custom cryptography here: request
signing, if ever enabled, uses Python's standard-library HMAC only and stays optional.
"""

import hashlib
import hmac
import json
from typing import Optional

from orderpilot_ai_worker.jobs.models import (
    AiJobSourceType,
    AiProcessingJobRequest,
    ProviderMode,
)

# Bound inbound content so a hostile/huge payload cannot exhaust memory or get echoed wholesale. The
# pipeline truncates again internally; this is the hard outer gate that rejects rather than truncates.
MAX_RAW_TEXT_CHARS = 50_000
MAX_METADATA_BYTES = 8_192

# Source types the worker will process. ``UNKNOWN`` is accepted (tagged low-confidence downstream);
# anything outside this enum never reaches the worker because the request model rejects it. This set
# exists so policy is explicit and so it can be tightened per-deployment later.
ALLOWED_SOURCE_TYPES = frozenset(AiJobSourceType)

# Provider modes the worker can actually run today. LOCAL_OLLAMA is selectable but gated a second time
# by its own config: it only reaches a local runtime when explicitly enabled (endpoint + model +
# transport) and otherwise fails closed downstream. FUTURE_SEMANTIC stays excluded — no such provider
# exists, so requesting it is rejected here.
ALLOWED_PIPELINES = frozenset(
    {ProviderMode.RULE_BASED, ProviderMode.MOCK_SEMANTIC, ProviderMode.LOCAL_OLLAMA}
)


class JobRejected(Exception):
    """Raised when a job envelope fails a fail-closed input control.

    ``reason`` is a short, bounded, non-sensitive token safe to return to Core API and to log. It
    never contains raw customer content, secrets, or internal stack detail.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def validate_job_envelope(request: AiProcessingJobRequest) -> str:
    """Validate a job envelope and return the bounded, safe text to extract.

    Raises ``JobRejected`` with a safe reason for any malformed/unsafe/too-large/unsupported input.
    """
    if not (request.job_id or "").strip():
        raise JobRejected("missing_job_id")
    if not (request.source_id or "").strip():
        raise JobRejected("missing_source_id")
    if not (request.tenant_ref or "").strip():
        raise JobRejected("missing_tenant_ref")

    if request.source_type not in ALLOWED_SOURCE_TYPES:
        raise JobRejected("unsupported_source_type")
    if request.requested_pipeline not in ALLOWED_PIPELINES:
        raise JobRejected("unsupported_pipeline")

    _assert_metadata_bounded(request.source_metadata)

    text = request.raw_text
    if text is None or not text.strip():
        # No inline text. References are accepted as references only; this PR never fetches external
        # URLs/object storage, so a reference-only job cannot be processed and fails closed.
        if request.text_ref or request.object_storage_ref:
            raise JobRejected("external_ref_fetch_unsupported")
        raise JobRejected("empty_text")

    if len(text) > MAX_RAW_TEXT_CHARS:
        raise JobRejected("raw_text_too_large")

    return text


def _assert_metadata_bounded(metadata: dict) -> None:
    try:
        encoded = json.dumps(metadata, default=str).encode("utf-8")
    except (TypeError, ValueError) as exc:  # non-serializable metadata is malformed input
        raise JobRejected("malformed_metadata") from exc
    if len(encoded) > MAX_METADATA_BYTES:
        raise JobRejected("metadata_too_large")


def compute_request_signature(secret: bytes, payload: bytes) -> str:
    """Standard-library HMAC-SHA256 helper for optional future signed requests.

    Provided so any later signing scheme uses a vetted standard primitive instead of home-grown
    crypto. It is not wired into the default flow and requires the caller to supply the secret out of
    band (never stored in this repo).
    """
    return hmac.new(secret, payload, hashlib.sha256).hexdigest()


def verify_request_signature(secret: bytes, payload: bytes, signature: Optional[str]) -> bool:
    """Constant-time verification companion to :func:`compute_request_signature`."""
    if not signature:
        return False
    expected = compute_request_signature(secret, payload)
    return hmac.compare_digest(expected, signature)
