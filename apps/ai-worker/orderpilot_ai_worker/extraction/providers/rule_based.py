"""Deterministic rule-based advisory extraction provider (OP-CAP-07B).

A richer, deterministic fallback that turns raw inbound text into a schema-valid, advisory-only
``ExtractionResult``. It extends — and does not replace — the existing
``MockSemanticExtractionProvider``: that provider is kept intact for its existing callers/tests.

Hard boundary: this provider only reads text and produces advisory structure. It performs no
business validation, no catalog/customer/price/inventory lookup, and has no mutation path. Final
SKU/customer/product/price/inventory decisions belong to Core API.
"""

import re
from typing import List, NamedTuple

from orderpilot_ai_worker.extraction.providers.semantic_extraction import (
    SemanticExtractionProvider,
)
from orderpilot_ai_worker.extraction.schemas.extraction import (
    ExtractedField,
    ExtractedLineItem,
    ExtractionResult,
    SourceEvidence,
)
from orderpilot_ai_worker.extraction.security.output_sanitizer import sanitize_text, validate_result

# Bound everything that can echo customer content so results/logs never carry unbounded payloads.
MAX_SNIPPET_LEN = 180
MAX_DESCRIPTION_LEN = 180

PROVIDER_NAME = "rule-based-understanding"
MODEL_NAME = "rule-based-v1"
SCHEMA_VERSION = "stage4.v1"

# Canonical advisory intents (superset of the legacy mock intents). Strings only — never actions.
INTENT_RFQ = "RFQ"
INTENT_PURCHASE_ORDER = "purchase_order"
INTENT_AVAILABILITY = "availability_inquiry"
INTENT_PRICE = "price_inquiry"
INTENT_ORDER_STATUS = "order_status_inquiry"
INTENT_SUBSTITUTE = "substitute_request"
INTENT_UNKNOWN = "unknown"

_VEHICLE_MAKES = (
    "toyota", "lexus", "honda", "acura", "nissan", "infiniti", "mazda", "mitsubishi", "subaru",
    "suzuki", "hyundai", "kia", "ford", "chevrolet", "gmc", "dodge", "jeep", "ram", "bmw",
    "mercedes", "mercedes-benz", "audi", "volkswagen", "vw", "skoda", "renault", "peugeot",
    "volvo", "scania", "man", "kamaz", "lada", "uaz", "gaz",
)

# Small deterministic city lexicon for a trailing location hint when no explicit "ship to" marker
# is present. This is an advisory hint only; Core API owns real branch/location resolution.
_KNOWN_CITIES = (
    "almaty", "astana", "nur-sultan", "shymkent", "aktau", "aktobe", "karaganda", "pavlodar",
    "taraz", "atyrau", "kostanay", "oral", "semey", "ust-kamenogorsk", "kyzylorda", "moscow",
    "saint petersburg", "tashkent", "bishkek", "baku", "tbilisi",
)

# Small parts lexicon used only as a secondary product-text hint. Advisory, never a catalog match.
_PARTS_LEXICON = (
    "brake pads", "brake pad", "brake disc", "brake discs", "brake rotor", "oil filter",
    "air filter", "fuel filter", "cabin filter", "spark plug", "spark plugs", "clutch",
    "alternator", "starter", "battery", "timing belt", "shock absorber", "wiper", "wipers",
    "radiator", "bearing", "bearings", "gasket", "headlight", "tire", "tires", "tyre", "tyres",
)

_QTY_WITH_UOM = re.compile(
    r"\b(\d{1,5})\s*(pcs|pc|pieces|piece|ea|units|unit|sets|set|boxes|box|pairs|pair|kg|kgs|l|liters|litres)\b",
    re.I,
)
_LINE_QTY = re.compile(
    r"\b(\d{1,5})\s*(x|pcs|pc|pieces|piece|ea|units|unit|sets|set|boxes|box|pairs|pair)\b",
    re.I,
)
# SKU/OEM token: must contain at least one letter AND one digit -> excludes plain years/PO numbers.
_SKU = re.compile(r"\b(?=[A-Z0-9][A-Z0-9._/-]*[A-Z])(?=[A-Z0-9._/-]*\d)[A-Z0-9][A-Z0-9._/-]{2,}\b")
_DATE = re.compile(r"\b(\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}/\d{2,4})\b")
_LOCATION_MARKER = re.compile(
    r"\b(?:ship(?:\s+to)?|deliver(?:\s+to)?|delivery(?:\s+to)?|location|branch|pickup(?:\s+at)?)[:\s]+([A-Za-z][A-Za-z .'-]{1,38})",
    re.I,
)
_CUSTOMER = re.compile(r"\b(?:customer|account|company|client)[:\s]+([^\n\r.;,]{2,60})", re.I)
_EMAIL = re.compile(r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b")
_PHONE = re.compile(r"(\+?\d[\d ()-]{6,}\d)")
_HANDLE = re.compile(r"(?<!\w)@[A-Za-z0-9_]{3,}\b")
_YEAR = re.compile(r"\b(19|20)\d{2}\b")
_UOM_NORMALIZE = {
    "pc": "PCS", "pcs": "PCS", "piece": "PCS", "pieces": "PCS", "ea": "EA", "unit": "EA",
    "units": "EA", "set": "SET", "sets": "SET", "box": "BOX", "boxes": "BOX", "pair": "PAIR",
    "pairs": "PAIR", "kg": "KG", "kgs": "KG", "l": "L", "liters": "L", "litres": "L", "x": "EA",
}

_PRODUCT_TRIGGER = re.compile(
    r"\b(?:need|needs|want|wants|looking for|require|requires|quote for|rfq for|price for|"
    r"send me|please send|do you have|substitute for|alternative for|replacement for)\s+"
    r"(?:a |an |some |me )?(.+)",
    re.I,
)
_PRODUCT_CUTTERS = re.compile(
    r"\s+(?:for\b|ship\b|deliver\b|delivery\b|to\b|by\b|location\b|branch\b|in\b|,|\.).*$",
    re.I,
)


class _Spans(NamedTuple):  # pylint: disable=too-few-public-methods
    quantity: re.Match[str] | None
    sku: re.Match[str] | None
    requested_date: re.Match[str] | None
    location: re.Match[str] | None
    customer: re.Match[str] | None
    contact: re.Match[str] | None


def _snippet(text: str, start: int, end: int) -> SourceEvidence:
    lo = max(0, start)
    hi = min(len(text), max(end, start))
    snippet = text[lo:hi][:MAX_SNIPPET_LEN]
    return SourceEvidence(
        evidence_type="TEXT_RANGE",
        snippet=snippet,
        start_offset=lo,
        end_offset=min(hi, lo + MAX_SNIPPET_LEN),
    )


def _normalize_uom(raw: str | None) -> str | None:
    if not raw:
        return None
    return _UOM_NORMALIZE.get(raw.lower(), raw.upper())


class RuleBasedExtractionProvider(SemanticExtractionProvider):  # pylint: disable=too-few-public-methods
    """Deterministic, advisory-only rule-based extractor for the understanding pipeline."""

    def extract(self, text: str, source_channel_context: str | None = None) -> ExtractionResult:
        """Return deterministic advisory extraction. Output is validated advisory-only."""
        safe_text = (sanitize_text(text) or "").strip()
        spans = _scan(safe_text)
        fields = _build_fields(safe_text, spans)
        line_items = _build_line_items(safe_text, spans)
        intent = classify_intent(safe_text)
        confidence = _document_confidence(fields, intent)
        result = ExtractionResult(
            detected_intent=intent,
            document_type="message",
            overall_confidence=confidence,
            document_confidence=confidence,
            extraction_method="rule_based",
            provider_name=PROVIDER_NAME,
            model_name=MODEL_NAME,
            schema_version=SCHEMA_VERSION,
            source_channel_context=source_channel_context,
            customer_hints=[spans.customer.group(1).strip()] if spans.customer else [],
            validation_status="ready_for_validation" if confidence >= 0.5 else "needs_review",
            fields=fields,
            line_items=line_items,
            evidence=[_snippet(safe_text, 0, min(len(safe_text), MAX_SNIPPET_LEN))] if safe_text else [],
        )
        return validate_result(result)


def classify_intent(text: str) -> str:
    """Map raw text to one advisory intent string. Deterministic; content is never executed."""
    lowered = text.lower()
    if any(k in lowered for k in ("substitute", "alternative", "replacement", "instead of",
                                  "equivalent", "cross reference", "cross-reference", "analog")):
        return INTENT_SUBSTITUTE
    if any(k in lowered for k in ("order status", "where is my order", "where's my order",
                                  "tracking", "track my order", "delivery status", "has it shipped")):
        return INTENT_ORDER_STATUS
    if "purchase order" in lowered or re.search(r"\bp\.?o\.?\s*#?\s*\d", lowered) or "po number" in lowered:
        return INTENT_PURCHASE_ORDER
    if any(k in lowered for k in ("in stock", "availability", "available", "do you have",
                                  "have stock", "any stock", "on hand")):
        return INTENT_AVAILABILITY
    if any(k in lowered for k in ("price", "cost", "how much", "pricing", "discount")):
        return INTENT_PRICE
    if any(k in lowered for k in ("rfq", "request for quote", "quote", "need", "want",
                                  "looking for", "require", "send me")):
        return INTENT_RFQ
    return INTENT_UNKNOWN


def _scan(text: str) -> _Spans:
    contact = _EMAIL.search(text) or _HANDLE.search(text) or _PHONE.search(text)
    return _Spans(
        quantity=_QTY_WITH_UOM.search(text),
        sku=_SKU.search(text.upper()),
        requested_date=_DATE.search(text),
        location=_LOCATION_MARKER.search(text),
        customer=_CUSTOMER.search(text),
        contact=contact,
    )


def _build_fields(text: str, spans: _Spans) -> List[ExtractedField]:  # noqa: C901
    fields: List[ExtractedField] = []
    if spans.quantity:
        fields.append(ExtractedField(
            field_name="quantity", raw_value=spans.quantity.group(1),
            normalized_value=spans.quantity.group(1), value_type="QUANTITY", confidence=0.8,
            evidence=_snippet(text, *spans.quantity.span()),
        ))
        fields.append(ExtractedField(
            field_name="uom", raw_value=spans.quantity.group(2),
            normalized_value=_normalize_uom(spans.quantity.group(2)), value_type="UOM",
            confidence=0.74, evidence=_snippet(text, *spans.quantity.span()),
        ))
    if spans.sku:
        fields.append(ExtractedField(
            field_name="raw_sku", raw_value=spans.sku.group(0),
            normalized_value=spans.sku.group(0), value_type="SKU", confidence=0.64,
            evidence=_snippet(text, *spans.sku.span()),
        ))
    product = _product_text(text)
    if product:
        start = text.lower().find(product.lower())
        fields.append(ExtractedField(
            field_name="requested_product_text", raw_value=product, normalized_value=product,
            value_type="PRODUCT_TEXT", confidence=0.6,
            evidence=_snippet(text, start, start + len(product)) if start >= 0 else None,
        ))
    fields.extend(_vehicle_fields(text))
    if spans.requested_date:
        fields.append(ExtractedField(
            field_name="requested_date", raw_value=spans.requested_date.group(1),
            normalized_value=spans.requested_date.group(1), value_type="DATE", confidence=0.58,
            evidence=_snippet(text, *spans.requested_date.span()),
        ))
    location = _location_hint(text, spans)
    if location:
        start = text.lower().find(location.lower())
        fields.append(ExtractedField(
            field_name="ship_to_location_hint", raw_value=location, normalized_value=location,
            value_type="LOCATION_HINT", confidence=0.55,
            evidence=_snippet(text, start, start + len(location)) if start >= 0 else None,
        ))
    if spans.customer:
        fields.append(ExtractedField(
            field_name="customer_hint", raw_value=spans.customer.group(1).strip(),
            normalized_value=spans.customer.group(1).strip(), value_type="CUSTOMER_HINT",
            confidence=0.6, evidence=_snippet(text, *spans.customer.span()),
        ))
    if spans.contact:
        fields.append(ExtractedField(
            field_name="contact_hint", raw_value=spans.contact.group(0).strip(),
            normalized_value=spans.contact.group(0).strip(), value_type="CONTACT_HINT",
            confidence=0.55, evidence=_snippet(text, *spans.contact.span()),
        ))
    return fields


def _vehicle_fields(text: str) -> List[ExtractedField]:
    lowered = text.lower()
    for make in _VEHICLE_MAKES:
        idx = lowered.find(make)
        if idx < 0:
            continue
        fields = [ExtractedField(
            field_name="vehicle_make", raw_value=text[idx:idx + len(make)],
            normalized_value=make.title(), value_type="VEHICLE_MAKE", confidence=0.6,
            evidence=_snippet(text, idx, idx + len(make)),
        )]
        after = text[idx + len(make):]
        model = re.search(r"\s+([A-Za-z][A-Za-z0-9-]{1,20})", after)
        if model and model.group(1).lower() not in ("for", "and", "the", "with"):
            m_start = idx + len(make) + model.start(1)
            fields.append(ExtractedField(
                field_name="vehicle_model", raw_value=model.group(1),
                normalized_value=model.group(1).title(), value_type="VEHICLE_MODEL",
                confidence=0.5, evidence=_snippet(text, m_start, m_start + len(model.group(1))),
            ))
        year = _YEAR.search(after)
        if year:
            y_start = idx + len(make) + year.start()
            fields.append(ExtractedField(
                field_name="vehicle_year", raw_value=year.group(0),
                normalized_value=year.group(0), value_type="VEHICLE_YEAR", confidence=0.55,
                evidence=_snippet(text, y_start, y_start + len(year.group(0))),
            ))
        return fields
    return []


def _product_text(text: str) -> str | None:
    match = _PRODUCT_TRIGGER.search(text)
    if match:
        candidate = match.group(1).strip()
        # Drop a leading "<qty> <uom>" so the product text is the part itself.
        candidate = re.sub(r"^\d{1,5}\s*[A-Za-z]{1,6}\s+", "", candidate)
        candidate = _PRODUCT_CUTTERS.sub("", candidate).strip(" ,.-")
        candidate = candidate[:MAX_DESCRIPTION_LEN]
        # Reject candidates that are just a SKU/number with no descriptive word.
        if candidate and re.search(r"[A-Za-z]", candidate) and not _SKU.fullmatch(candidate.upper()):
            if len(candidate) >= 3:
                return candidate
    lowered = text.lower()
    for part in _PARTS_LEXICON:
        if part in lowered:
            return part
    return None


def _location_hint(text: str, spans: _Spans) -> str | None:
    if spans.location:
        return spans.location.group(1).strip()
    lowered = text.lower()
    for city in _KNOWN_CITIES:
        if re.search(rf"\b{re.escape(city)}\b", lowered):
            idx = lowered.find(city)
            return text[idx:idx + len(city)]
    return None


def _build_line_items(text: str, spans: _Spans) -> List[ExtractedLineItem]:
    lines = [ln for ln in text.splitlines() if ln.strip()]
    items: List[ExtractedLineItem] = []
    for line in lines:
        qty = _LINE_QTY.search(line)
        if qty is None:
            continue
        sku = _SKU.search(line.upper())
        line_offset = text.find(line)
        items.append(ExtractedLineItem(
            line_number=len(items) + 1,
            raw_sku=sku.group(0) if sku else None,
            raw_alias=sku.group(0) if sku else None,
            raw_description=line.strip()[:MAX_DESCRIPTION_LEN],
            raw_quantity=qty.group(1),
            raw_uom=_normalize_uom(qty.group(2)),
            confidence=0.6,
            evidence=_snippet(text, max(0, line_offset), max(0, line_offset) + len(line)),
        ))
    if items:
        return items
    # Fallback: a single document-level item when any business field was found.
    if spans.quantity or spans.sku or _product_text(text):
        return [ExtractedLineItem(
            line_number=1,
            raw_sku=spans.sku.group(0) if spans.sku else None,
            raw_alias=spans.sku.group(0) if spans.sku else None,
            raw_description=(_product_text(text) or text.strip())[:MAX_DESCRIPTION_LEN],
            raw_quantity=spans.quantity.group(1) if spans.quantity else None,
            raw_uom=_normalize_uom(spans.quantity.group(2)) if spans.quantity else None,
            requested_date=spans.requested_date.group(1) if spans.requested_date else None,
            ship_to_location_hint=_location_hint(text, spans),
            confidence=0.58,
            evidence=_snippet(text, 0, min(len(text), MAX_SNIPPET_LEN)),
        )]
    return []


def _document_confidence(fields: List[ExtractedField], intent: str) -> float:
    if not fields:
        return 0.3 if intent != INTENT_UNKNOWN else 0.2
    base = 0.45 + 0.07 * len(fields)
    return round(min(base, 0.92), 3)
