"""Tests for advisory inbound document processing."""

import pytest
from pydantic import ValidationError

from orderpilot_ai_worker.schemas.extraction_result import ExtractionResult
from orderpilot_ai_worker.security.ai_safety import assert_advisory_task
from orderpilot_ai_worker.tasks.process_inbound_document import process_inbound_document


def test_process_inbound_document_returns_advisory_result() -> None:
    """Processing an inbound document returns advisory-only output."""
    result = process_inbound_document("doc-1", "Need 20 filters")

    assert result.document_id == "doc-1"
    assert result.advisory_only is True
    assert result.confidence == 0.5
    assert result.warnings


def test_schema_rejects_invalid_confidence() -> None:
    """Extraction result schemas reject invalid confidence values."""
    with pytest.raises(ValidationError):
        ExtractionResult.model_validate(
            {
                "document_id": "doc-1",
                "summary": "bad",
                "confidence": 2.0,
            }
        )


def test_ai_worker_rejects_forbidden_business_mutation() -> None:
    """AI worker safety guard rejects trusted business mutations."""
    with pytest.raises(ValueError):
        assert_advisory_task("create_order")
