"""Semantic extraction provider contracts and deterministic mock provider."""

from abc import ABC, abstractmethod
import re

from orderpilot_ai_worker.extraction.schemas.extraction import (
    AiSuggestion,
    ExtractedField,
    ExtractedLineItem,
    ExtractionResult,
    SourceEvidence,
)
from orderpilot_ai_worker.extraction.security.prompt_injection import detect_prompt_injection
from orderpilot_ai_worker.extraction.security.output_sanitizer import sanitize_text, validate_result


class SemanticExtractionProvider(ABC):  # pylint: disable=too-few-public-methods
    """Provider boundary for advisory semantic extraction."""

    @abstractmethod
    def extract(
        self,
        text: str,
        source_channel_context: str | None = None,
    ) -> ExtractionResult:
        """Return advisory structured extraction only."""


# pylint: disable-next=too-few-public-methods
class MockSemanticExtractionProvider(SemanticExtractionProvider):
    """Rule-based semantic extractor used for local advisory worker tests."""

    def extract(
        self,
        text: str,
        source_channel_context: str | None = None,
    ) -> ExtractionResult:
        """Return deterministic advisory extraction output."""
        safe_text = sanitize_text(text) or ""
        warnings = detect_prompt_injection(safe_text)
        quantity_match = re.search(
            r"\b(\d+(?:\.\d+)?)\s*(EA|PCS|PC|BOX|SET)?\b",
            safe_text,
            re.I,
        )
        sku_match = re.search(
            r"\b(?=[A-Z0-9._-]*\d)[A-Z0-9][A-Z0-9._-]{2,}\b",
            safe_text.upper(),
        )
        requested_date = re.search(
            r"\b(\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{2,4})\b",
            safe_text,
        )
        location = re.search(
            r"\b(?:ship(?:\s+to)?|deliver(?:\s+to)?|location)[:\s]+([A-Za-z0-9 ._-]{2,40})",
            safe_text,
            re.I,
        )
        customer = re.search(
            r"\b(?:customer|account|company)[:\s]+([^\n\r.;]{2,60})",
            safe_text,
            re.I,
        )
        evidence = SourceEvidence(
            evidence_type="TEXT_RANGE",
            snippet=safe_text[:180],
            start_offset=0,
            end_offset=min(len(safe_text), 180),
        )
        fields: list[ExtractedField] = []
        if quantity_match:
            fields.append(
                ExtractedField(
                    field_name="quantity",
                    raw_value=quantity_match.group(1),
                    normalized_value=quantity_match.group(1),
                    value_type="QUANTITY",
                    confidence=0.78,
                    evidence=evidence,
                )
            )
            if quantity_match.group(2):
                fields.append(
                    ExtractedField(
                        field_name="uom",
                        raw_value=quantity_match.group(2),
                        normalized_value=quantity_match.group(2).upper(),
                        value_type="UOM",
                        confidence=0.74,
                        evidence=evidence,
                    )
                )
        if sku_match:
            fields.append(
                ExtractedField(
                    field_name="raw_sku",
                    raw_value=sku_match.group(0),
                    normalized_value=sku_match.group(0),
                    value_type="SKU",
                    confidence=0.66,
                    evidence=evidence,
                )
            )
        if requested_date:
            fields.append(
                ExtractedField(
                    field_name="requested_date",
                    raw_value=requested_date.group(1),
                    normalized_value=requested_date.group(1),
                    value_type="DATE",
                    confidence=0.58,
                    evidence=evidence,
                )
            )
        if location:
            fields.append(
                ExtractedField(
                    field_name="ship_to_location_hint",
                    raw_value=location.group(1).strip(),
                    normalized_value=location.group(1).strip(),
                    value_type="LOCATION_HINT",
                    confidence=0.56,
                    evidence=evidence,
                )
            )
        line_items = (
            [
                ExtractedLineItem(
                    line_number=1,
                    raw_sku=sku_match.group(0) if sku_match else None,
                    raw_alias=sku_match.group(0) if sku_match else None,
                    raw_description=safe_text[:180],
                    raw_quantity=quantity_match.group(1) if quantity_match else None,
                    raw_uom=(
                        quantity_match.group(2).upper()
                        if quantity_match and quantity_match.group(2)
                        else "EA"
                    ),
                    requested_date=requested_date.group(1) if requested_date else None,
                    ship_to_location_hint=location.group(1).strip() if location else None,
                    confidence=0.62,
                    evidence=evidence,
                )
            ]
            if fields
            else []
        )
        suggestions = [
            AiSuggestion(
                suggestion_type="WARNING",
                suggestion={"warning": warning},
                confidence=0.9,
            )
            for warning in warnings
        ]
        lowered = safe_text.lower()
        if "purchase order" in lowered or " po " in lowered:
            intent = "purchase_order"
        elif "order status" in lowered or "where is my order" in lowered or "tracking" in lowered:
            intent = "order_status_question"
        elif (
            "price" in lowered
            or "compatible" in lowered
            or "does this fit" in lowered
            or "question" in lowered
        ):
            intent = "product_question"
        elif "need" in lowered or "rfq" in lowered or "quote" in lowered:
            intent = "RFQ"
        else:
            intent = "unknown"
        overall_confidence = 0.7 if fields and not warnings else 0.35
        result = ExtractionResult(
            detected_intent=intent,
            document_type="message",
            overall_confidence=overall_confidence,
            source_channel_context=source_channel_context,
            customer_hints=[customer.group(1).strip()] if customer else [],
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
