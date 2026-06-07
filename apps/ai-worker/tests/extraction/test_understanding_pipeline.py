"""Tests for the OP-CAP-07B advisory understanding pipeline.

These cover the extraction/understanding layer only — not business validation, catalog matching,
inventory, price, or any mutation (those belong to Core API).
"""

import json

import pytest
from pydantic import ValidationError

from orderpilot_ai_worker.extraction.pipeline import (
    ExtractionInput,
    SemanticExtractionPipeline,
    run_extraction,
)
from orderpilot_ai_worker.extraction.providers.rule_based import (
    MAX_SNIPPET_LEN,
    RuleBasedExtractionProvider,
    classify_intent,
)
from orderpilot_ai_worker.extraction.schemas.extraction import ExtractionResult


def _field(result: ExtractionResult, name: str):
    return next((f for f in result.fields if f.field_name == name), None)


def _run(text: str, source_type: str = "channel_message", source_id: str = "m1") -> ExtractionResult:
    return run_extraction(
        ExtractionInput(source_type=source_type, source_id=source_id, raw_text=text)
    )


def test_rfq_message_full_extraction() -> None:
    """The canonical RFQ example yields intent, product, vehicle, qty/uom, location, evidence."""
    result = _run("Need brake pads for Toyota Camry 2018, 20 pcs, wholesale, Almaty")

    assert result.detected_intent == "RFQ"
    assert _field(result, "requested_product_text").raw_value == "brake pads"
    assert _field(result, "vehicle_make").normalized_value == "Toyota"
    assert _field(result, "vehicle_model").normalized_value == "Camry"
    assert _field(result, "vehicle_year").raw_value == "2018"
    assert _field(result, "quantity").raw_value == "20"
    assert _field(result, "uom").normalized_value == "PCS"
    assert _field(result, "ship_to_location_hint").raw_value.lower() == "almaty"
    # Confidence + evidence present.
    assert 0.0 < result.overall_confidence <= 1.0
    assert result.document_confidence is not None
    assert all(0.0 <= f.confidence <= 1.0 for f in result.fields)
    assert _field(result, "quantity").evidence is not None
    assert result.line_items and result.line_items[0].raw_quantity == "20"
    # Identity + advisory metadata wired by the pipeline.
    assert result.extraction_id and result.source_type == "channel_message"
    assert result.source_id == "m1"
    assert result.advisory_only is True


def test_multiple_line_items_from_po_text() -> None:
    """Multi-line PO text yields one line item per quantity-bearing line, skipping the header."""
    result = _run("PO 12345\n2 x SKU-100 brake pads\n3 pcs FLT-22 oil filter", source_type="email")

    assert result.detected_intent == "purchase_order"
    assert len(result.line_items) == 2
    skus = {li.raw_sku for li in result.line_items}
    assert skus == {"SKU-100", "FLT-22"}
    assert result.line_items[0].raw_quantity == "2"
    assert result.line_items[1].raw_uom == "PCS"


@pytest.mark.parametrize(
    "text,expected",
    [
        ("Do you have brake pads in stock for Toyota Camry?", "availability_inquiry"),
        ("Need a substitute for FLT-22 oil filter", "substitute_request"),
        ("What is the price for SKU-100?", "price_inquiry"),
        ("Where is my order? tracking please", "order_status_inquiry"),
        ("hello there", "unknown"),
    ],
)
def test_intent_classification(text: str, expected: str) -> None:
    """Intent classification covers the advisory vocabulary deterministically."""
    assert classify_intent(text) == expected
    assert _run(text).detected_intent == expected


def test_empty_text_produces_controlled_failure() -> None:
    """Empty/whitespace input returns a controlled failed result, never a crash or business state."""
    result = _run("   ")
    assert result.validation_status == "failed"
    assert result.detected_intent == "unknown"
    assert result.warnings == ["empty_input"]
    assert result.fields == [] and result.line_items == []
    assert result.advisory_only is True


def test_unsupported_source_type_is_tagged_not_crashed() -> None:
    """An unknown source type is accepted and tagged, never crashed on."""
    result = _run("Need 5 EA SKU-1", source_type="carrier_pigeon")
    assert "unsupported_source_type" in result.warnings
    assert result.advisory_only is True


def test_invalid_provider_output_is_rejected_by_pipeline() -> None:
    """A provider returning a non-ExtractionResult or non-advisory result cannot pass silently."""

    class _DictProvider(RuleBasedExtractionProvider):
        def extract(self, text, source_channel_context=None):  # type: ignore[override]
            return {"detected_intent": "RFQ"}  # wrong shape

    class _NonAdvisoryProvider(RuleBasedExtractionProvider):
        def extract(self, text, source_channel_context=None):  # type: ignore[override]
            return ExtractionResult(
                detected_intent="RFQ", document_type="message", overall_confidence=0.9,
                advisory_only=False,
            )

    bad = SemanticExtractionPipeline(provider=_DictProvider()).run(
        ExtractionInput(source_type="channel_message", source_id="m1", raw_text="Need 5 EA SKU-1")
    )
    assert bad.validation_status == "failed"
    assert bad.warnings == ["invalid_provider_output"]

    non_advisory = SemanticExtractionPipeline(provider=_NonAdvisoryProvider()).run(
        ExtractionInput(source_type="channel_message", source_id="m1", raw_text="Need 5 EA SKU-1")
    )
    assert non_advisory.validation_status == "failed"
    assert non_advisory.advisory_only is True


def test_provider_exception_becomes_controlled_failure() -> None:
    """A provider raising does not leak internals; it becomes a controlled failed result."""

    class _BoomProvider(RuleBasedExtractionProvider):
        def extract(self, text, source_channel_context=None):  # type: ignore[override]
            raise RuntimeError("secret internal detail with customer text")

    result = SemanticExtractionPipeline(provider=_BoomProvider()).run(
        ExtractionInput(source_type="channel_message", source_id="m1", raw_text="Need 5 EA SKU-1")
    )
    assert result.validation_status == "failed"
    assert result.warnings == ["provider_error"]
    assert "secret internal detail" not in json.dumps(result.model_dump())


def test_schema_rejects_out_of_range_confidence() -> None:
    """Schema validation rejects malformed confidence values outright."""
    with pytest.raises(ValidationError):
        ExtractionResult(detected_intent="RFQ", document_type="message", overall_confidence=5.0)


def test_snippet_and_input_are_bounded() -> None:
    """Long input is truncated and evidence snippets stay bounded."""
    long_text = "Need brake pads " + ("x" * 50_000)
    result = _run(long_text)
    assert "input_truncated" in result.warnings
    for f in result.fields:
        if f.evidence and f.evidence.snippet is not None:
            assert len(f.evidence.snippet) <= MAX_SNIPPET_LEN


def test_json_serialization_round_trip() -> None:
    """ExtractionResult serializes to JSON and reloads identically."""
    result = _run("Need brake pads for Toyota Camry 2018, 20 pcs, Almaty")
    as_json = result.model_dump_json()
    reloaded = ExtractionResult.model_validate_json(as_json)
    assert reloaded.model_dump() == result.model_dump()
