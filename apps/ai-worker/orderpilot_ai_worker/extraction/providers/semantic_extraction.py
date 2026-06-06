"""Semantic extraction provider contracts and deterministic mock provider."""

from abc import ABC, abstractmethod
import re
from typing import List, NamedTuple

from orderpilot_ai_worker.extraction.schemas.extraction import (
    AiSuggestion,
    ExtractedField,
    ExtractedLineItem,
    ExtractionResult,
    SourceEvidence,
)
from orderpilot_ai_worker.extraction.security.prompt_injection import detect_prompt_injection
from orderpilot_ai_worker.extraction.security.output_sanitizer import sanitize_text, validate_result


class _ExtractionMatches(NamedTuple):  # pylint: disable=too-few-public-methods
    quantity: re.Match[str] | None
    sku: re.Match[str] | None
    requested_date: re.Match[str] | None
    location: re.Match[str] | None
    customer: re.Match[str] | None


class SemanticExtractionProvider(ABC):  # pylint: disable=too-few-public-methods
    """Provider boundary for advisory semantic extraction."""

    @abstractmethod
    def extract(self, text: str, source_channel_context: str | None = None) -> ExtractionResult:
        """Return advisory structured extraction only."""


class MockSemanticExtractionProvider(SemanticExtractionProvider):  # pylint: disable=too-few-public-methods
    """Rule-based semantic extractor used for local advisory worker tests."""

    def extract(self, text: str, source_channel_context: str | None = None) -> ExtractionResult:
        """Return deterministic advisory extraction output."""
        safe_text = sanitize_text(text) or ""
        warnings = detect_prompt_injection(safe_text)
        matches = _match_all(safe_text)
        evidence = _source_evidence(safe_text)
        fields = _build_fields(matches, evidence)
        line_items = _build_line_items(safe_text, fields, matches, evidence)
        suggestions = _build_suggestions(warnings)
        overall_confidence = 0.7 if fields and not warnings else 0.35
        result = ExtractionResult(
            detected_intent=_detect_intent(safe_text),
            document_type="message",
            overall_confidence=overall_confidence,
            source_channel_context=source_channel_context,
            customer_hints=[matches.customer.group(1).strip()] if matches.customer else [],
            validation_status=(
                "ready_for_validation"
                if overall_confidence >= 0.5 and not warnings
                else "needs_review"
            ),
            fields=fields,
            line_items=line_items,
            evidence=[evidence],
            suggestions=suggestions,
        )
        return validate_result(result)


def _match_all(text: str) -> _ExtractionMatches:
    return _ExtractionMatches(
        quantity=_match_quantity(text),
        sku=_match_sku(text),
        requested_date=_match_requested_date(text),
        location=_match_location(text),
        customer=_match_customer(text),
    )


def _match_quantity(text: str) -> re.Match[str] | None:
    return re.search(r"\b(\d+(?:\.\d+)?)\s*(EA|PCS|PC|BOX|SET)?\b", text, re.I)


def _match_sku(text: str) -> re.Match[str] | None:
    return re.search(r"\b(?=[A-Z0-9._-]*\d)[A-Z0-9][A-Z0-9._-]{2,}\b", text.upper())


def _match_requested_date(text: str) -> re.Match[str] | None:
    return re.search(r"\b(\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{2,4})\b", text)


def _match_location(text: str) -> re.Match[str] | None:
    return re.search(
        r"\b(?:ship(?:\s+to)?|deliver(?:\s+to)?|location)[:\s]+([A-Za-z0-9 ._-]{2,40})",
        text,
        re.I,
    )


def _match_customer(text: str) -> re.Match[str] | None:
    return re.search(r"\b(?:customer|account|company)[:\s]+([^\n\r.;]{2,60})", text, re.I)


def _source_evidence(text: str) -> SourceEvidence:
    return SourceEvidence(
        evidence_type="TEXT_RANGE",
        snippet=text[:180],
        start_offset=0,
        end_offset=min(len(text), 180),
    )


def _build_fields(
    matches: _ExtractionMatches,
    evidence: SourceEvidence,
) -> List[ExtractedField]:
    fields: List[ExtractedField] = []
    if matches.quantity:
        fields.append(_quantity_field(matches.quantity, evidence))
        if matches.quantity.group(2):
            fields.append(_uom_field(matches.quantity, evidence))
    if matches.sku:
        fields.append(_sku_field(matches.sku, evidence))
    if matches.requested_date:
        fields.append(_requested_date_field(matches.requested_date, evidence))
    if matches.location:
        fields.append(_location_field(matches.location, evidence))
    return fields


def _quantity_field(quantity_match: re.Match[str], evidence: SourceEvidence) -> ExtractedField:
    return ExtractedField(
        field_name="quantity",
        raw_value=quantity_match.group(1),
        normalized_value=quantity_match.group(1),
        value_type="QUANTITY",
        confidence=0.78,
        evidence=evidence,
    )


def _uom_field(quantity_match: re.Match[str], evidence: SourceEvidence) -> ExtractedField:
    return ExtractedField(
        field_name="uom",
        raw_value=quantity_match.group(2),
        normalized_value=quantity_match.group(2).upper(),
        value_type="UOM",
        confidence=0.74,
        evidence=evidence,
    )


def _sku_field(sku_match: re.Match[str], evidence: SourceEvidence) -> ExtractedField:
    return ExtractedField(
        field_name="raw_sku",
        raw_value=sku_match.group(0),
        normalized_value=sku_match.group(0),
        value_type="SKU",
        confidence=0.66,
        evidence=evidence,
    )


def _requested_date_field(
    requested_date: re.Match[str], evidence: SourceEvidence
) -> ExtractedField:
    return ExtractedField(
        field_name="requested_date",
        raw_value=requested_date.group(1),
        normalized_value=requested_date.group(1),
        value_type="DATE",
        confidence=0.58,
        evidence=evidence,
    )


def _location_field(location: re.Match[str], evidence: SourceEvidence) -> ExtractedField:
    return ExtractedField(
        field_name="ship_to_location_hint",
        raw_value=location.group(1).strip(),
        normalized_value=location.group(1).strip(),
        value_type="LOCATION_HINT",
        confidence=0.56,
        evidence=evidence,
    )


def _build_line_items(
    text: str,
    fields: List[ExtractedField],
    matches: _ExtractionMatches,
    evidence: SourceEvidence,
) -> List[ExtractedLineItem]:
    if not fields:
        return []
    return [
        ExtractedLineItem(
            line_number=1,
            raw_sku=matches.sku.group(0) if matches.sku else None,
            raw_alias=matches.sku.group(0) if matches.sku else None,
            raw_description=text[:180],
            raw_quantity=matches.quantity.group(1) if matches.quantity else None,
            raw_uom=_raw_uom(matches.quantity),
            requested_date=matches.requested_date.group(1) if matches.requested_date else None,
            ship_to_location_hint=matches.location.group(1).strip() if matches.location else None,
            confidence=0.62,
            evidence=evidence,
        )
    ]


def _raw_uom(quantity_match: re.Match[str] | None) -> str:
    if quantity_match and quantity_match.group(2):
        return quantity_match.group(2).upper()
    return "EA"


def _build_suggestions(warnings: List[str]) -> List[AiSuggestion]:
    return [
        AiSuggestion(
            suggestion_type="WARNING",
            suggestion={"warning": warning},
            confidence=0.9,
        )
        for warning in warnings
    ]


def _detect_intent(text: str) -> str:
    lowered = text.lower()
    if "purchase order" in lowered or " po " in lowered:
        return "purchase_order"
    if "order status" in lowered or "where is my order" in lowered or "tracking" in lowered:
        return "order_status_question"
    if (
        "price" in lowered
        or "compatible" in lowered
        or "does this fit" in lowered
        or "question" in lowered
    ):
        return "product_question"
    if "need" in lowered or "rfq" in lowered or "quote" in lowered:
        return "RFQ"
    return "unknown"
