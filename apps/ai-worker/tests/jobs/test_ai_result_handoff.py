"""Tests for the OP-CAP-07C secure handoff contract.

Assert that the in-memory sink accepts bounded, schema-valid, correlated results and that the handoff
invariants reject missing correlation, oversized payloads, and any action surface.
"""

import pytest
from pydantic import ValidationError

from orderpilot_ai_worker.extraction.schemas.extraction import AiSuggestion
from orderpilot_ai_worker.jobs.handler import process_ai_extraction_job
from orderpilot_ai_worker.jobs.handoff import (
    HandoffRejected,
    InMemoryResultSink,
    assert_handoff_safe,
)
from orderpilot_ai_worker.jobs.models import (
    AiJobSourceType,
    AiJobStatus,
    AiProcessingJobRequest,
    AiProcessingJobResult,
)


def _request(text: str) -> AiProcessingJobRequest:
    return AiProcessingJobRequest(
        job_id="job-1",
        tenant_ref="tenant-1",
        source_type=AiJobSourceType.CHANNEL_MESSAGE,
        source_id="m1",
        raw_text=text,
    )


def test_sink_accepts_bounded_schema_valid_result() -> None:
    """A processed advisory result publishes through the in-memory sink and is captured."""
    result = process_ai_extraction_job(
        _request("Need brake pads for Toyota Camry 2018, 20 pcs, Almaty")
    )
    sink = InMemoryResultSink()

    outcome = sink.publish_result(result)

    assert outcome.accepted is True and outcome.job_id == "job-1"
    assert sink.published == [result]


def test_rejected_job_can_still_be_handed_off_safely() -> None:
    """Even a REJECTED job is a valid, correlated handoff payload (Core API records the rejection)."""
    result = process_ai_extraction_job(_request("   "))
    assert result.status == AiJobStatus.REJECTED

    sink = InMemoryResultSink()
    assert sink.publish_result(result).accepted is True


def test_handoff_requires_correlation() -> None:
    """Missing job/source/tenant correlation is rejected by the handoff guard."""
    result = process_ai_extraction_job(_request("Need 5 EA SKU-1"))

    for field, reason in (
        ("job_id", "missing_job_id"),
        ("source_id", "missing_source_id"),
        ("tenant_ref", "missing_tenant_ref"),
        ("schema_version", "missing_schema_version"),
    ):
        broken = result.model_copy(update={field: "  "})
        with pytest.raises(HandoffRejected) as exc:
            assert_handoff_safe(broken)
        assert exc.value.reason == reason


def test_handoff_rejects_oversized_payload(monkeypatch) -> None:
    """An unexpectedly large payload is rejected rather than shipped to Core API."""
    import orderpilot_ai_worker.jobs.handoff as handoff_module

    result = process_ai_extraction_job(_request("Need 5 EA SKU-1"))
    monkeypatch.setattr(handoff_module, "MAX_RESULT_PAYLOAD_BYTES", 10)

    with pytest.raises(HandoffRejected) as exc:
        assert_handoff_safe(result)
    assert exc.value.reason == "payload_too_large"


def test_handoff_payload_has_no_action_surface() -> None:
    """The serialized handoff payload exposes no executable/mutation top-level key."""
    result = process_ai_extraction_job(_request("Need 5 EA SKU-1"))
    payload = result.model_dump(mode="json")

    for forbidden in ("action", "command", "approve", "execute", "write", "mutation", "sql", "erp_write"):
        assert forbidden not in payload


def test_handoff_rejects_nested_unsafe_model_output() -> None:
    """Nested action/authority/connector keys in advisory extraction output fail closed."""
    result = process_ai_extraction_job(_request("Need 5 EA SKU-1"))
    assert result.extraction_result is not None
    unsafe_extraction = result.extraction_result.model_copy(
        update={
            "suggestions": [
                AiSuggestion(
                    suggestion_type="unsafe",
                    suggestion={"erpWrite": {"order": "create"}},
                    confidence=0.9,
                )
            ]
        }
    )
    unsafe = result.model_copy(update={"extraction_result": unsafe_extraction})

    with pytest.raises(HandoffRejected) as exc:
        assert_handoff_safe(unsafe)
    assert exc.value.reason == "forbidden_action_surface"


def test_result_contract_rejects_unknown_top_level_fields() -> None:
    """The worker result model must not silently accept authority/action fields."""
    with pytest.raises(ValidationError):
        AiProcessingJobResult(
            job_id="job-1",
            tenant_ref="tenant-1",
            source_type=AiJobSourceType.CHANNEL_MESSAGE,
            source_id="m1",
            status=AiJobStatus.SUCCEEDED,
            approve=True,
        )
