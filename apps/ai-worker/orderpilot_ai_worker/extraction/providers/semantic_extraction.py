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


class SemanticExtractionProvider(ABC):
    @abstractmethod
    def extract(self, text: str) -> ExtractionResult:
        """Return advisory structured extraction only."""


class MockSemanticExtractionProvider(SemanticExtractionProvider):
    def extract(self, text: str) -> ExtractionResult:
        safe_text = sanitize_text(text) or ""
        warnings = detect_prompt_injection(safe_text)
        quantity_match = re.search(r"\b(\d+(?:\.\d+)?)\s*(EA|PCS|PC|BOX|SET)?\b", safe_text, re.I)
        sku_match = re.search(r"\b[A-Z0-9][A-Z0-9._-]{2,}\b", safe_text.upper())
        evidence = SourceEvidence(evidence_type="TEXT_RANGE", snippet=safe_text[:180], start_offset=0, end_offset=min(len(safe_text), 180))
        fields: list[ExtractedField] = []
        if quantity_match:
            fields.append(ExtractedField(field_name="quantity", raw_value=quantity_match.group(1), normalized_value=quantity_match.group(1), value_type="QUANTITY", confidence=0.78, evidence=evidence))
        if sku_match:
            fields.append(ExtractedField(field_name="sku", raw_value=sku_match.group(0), normalized_value=sku_match.group(0), value_type="SKU", confidence=0.66, evidence=evidence))
        line_items = [
            ExtractedLineItem(line_number=1, raw_sku=sku_match.group(0) if sku_match else None, raw_description=safe_text[:180], raw_quantity=quantity_match.group(1) if quantity_match else None, raw_uom="EA", confidence=0.62, evidence=evidence)
        ] if fields else []
        suggestions = [AiSuggestion(suggestion_type="WARNING", suggestion={"warning": warning}, confidence=0.9) for warning in warnings]
        result = ExtractionResult(
            detected_intent="RFQ" if "need" in safe_text.lower() else "UNKNOWN",
            document_type="MESSAGE",
            overall_confidence=0.7 if fields else 0.35,
            fields=fields,
            line_items=line_items,
            evidence=[evidence],
            suggestions=suggestions,
        )
        return validate_result(result)