"""Security-envelope tests for OP-CAP-07C job handling.

Assert the fail-closed input controls (bounds, required correlation, allowed types, no external
fetch) and that no result/log carries secrets or any business mutation/action surface.
"""

import json

import pytest
from pydantic import ValidationError

from orderpilot_ai_worker.jobs import security as security_module
from orderpilot_ai_worker.jobs.handler import process_ai_extraction_job
from orderpilot_ai_worker.jobs.models import (
    AiJobSourceType,
    AiJobStatus,
    AiProcessingJobRequest,
)
from orderpilot_ai_worker.jobs.security import (
    MAX_RAW_TEXT_CHARS,
    compute_request_signature,
    verify_request_signature,
)


def _request(text: str | None, **overrides) -> AiProcessingJobRequest:
    base = dict(
        job_id="job-1",
        tenant_ref="tenant-1",
        source_type=AiJobSourceType.CHANNEL_MESSAGE,
        source_id="m1",
        raw_text=text,
    )
    base.update(overrides)
    return AiProcessingJobRequest(**base)


def test_empty_text_is_rejected_with_safe_reason() -> None:
    """Empty/whitespace text fails closed as REJECTED with a bounded reason and no extraction."""
    result = process_ai_extraction_job(_request("   "))
    assert result.status == AiJobStatus.REJECTED
    assert result.safe_failure_reason == "empty_text"
    assert result.extraction_result is None


def test_too_large_text_is_rejected() -> None:
    """Oversized raw text is rejected outright, not truncated-and-processed."""
    result = process_ai_extraction_job(_request("x" * (MAX_RAW_TEXT_CHARS + 1)))
    assert result.status == AiJobStatus.REJECTED
    assert result.safe_failure_reason == "raw_text_too_large"


def test_reference_only_job_is_rejected_no_external_fetch() -> None:
    """A job carrying only an object-storage/text reference is rejected; the worker never fetches."""
    result = process_ai_extraction_job(
        _request(None, object_storage_ref="s3://bucket/key", source_type=AiJobSourceType.PDF_TEXT)
    )
    assert result.status == AiJobStatus.REJECTED
    assert result.safe_failure_reason == "external_ref_fetch_unsupported"


def test_missing_correlation_is_rejected() -> None:
    """Missing job/source/tenant correlation fails closed."""
    for field in ("job_id", "source_id", "tenant_ref"):
        result = process_ai_extraction_job(_request("Need 5 EA SKU-1", **{field: "  "}))
        assert result.status == AiJobStatus.REJECTED
        assert result.safe_failure_reason == f"missing_{field}"


def test_oversized_metadata_is_rejected() -> None:
    """Metadata beyond the bound is rejected rather than processed."""
    big_meta = {"blob": "y" * (security_module.MAX_METADATA_BYTES + 10)}
    result = process_ai_extraction_job(_request("Need 5 EA SKU-1", source_metadata=big_meta))
    assert result.status == AiJobStatus.REJECTED
    assert result.safe_failure_reason == "metadata_too_large"


def test_unsupported_source_type_string_rejected_at_boundary() -> None:
    """An unknown source type cannot even be constructed — the wire contract fails closed."""
    with pytest.raises(ValidationError):
        AiProcessingJobRequest(
            job_id="job-1",
            tenant_ref="tenant-1",
            source_type="CARRIER_PIGEON",  # not a valid AiJobSourceType
            source_id="m1",
            raw_text="Need 5 EA SKU-1",
        )


def test_source_type_outside_allowed_set_is_rejected(monkeypatch) -> None:
    """The runtime allow-list guard can tighten policy below the enum and yields REJECTED."""
    monkeypatch.setattr(
        security_module,
        "ALLOWED_SOURCE_TYPES",
        frozenset({AiJobSourceType.CHANNEL_MESSAGE}),
    )
    result = process_ai_extraction_job(
        _request("Need 5 EA SKU-1", source_type=AiJobSourceType.EMAIL_BODY)
    )
    assert result.status == AiJobStatus.REJECTED
    assert result.safe_failure_reason == "unsupported_source_type"


def test_no_secrets_or_action_surface_in_result() -> None:
    """No secret-shaped key and no business mutation/action key appears anywhere in the result."""
    payload = process_ai_extraction_job(
        _request("Need brake pads for Toyota Camry 2018, 20 pcs, Almaty")
    ).model_dump_json()
    lowered = payload.lower()
    for secret_marker in ("api_key", "apikey", "secret", "password", "authorization", "bearer "):
        assert secret_marker not in lowered
    for action_marker in ('"action"', '"command"', '"approve"', '"create_order"', '"update_stock"'):
        assert action_marker not in lowered


def test_hmac_signing_helper_uses_stdlib_and_verifies() -> None:
    """The optional signing helper is standard HMAC-SHA256, not home-grown crypto."""
    secret = b"out-of-band-test-secret-not-in-repo"
    payload = json.dumps({"job_id": "job-1"}).encode("utf-8")
    signature = compute_request_signature(secret, payload)

    assert verify_request_signature(secret, payload, signature) is True
    assert verify_request_signature(secret, payload, "deadbeef") is False
    assert verify_request_signature(secret, payload, None) is False
