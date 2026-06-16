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
    AiSuggestion,
    CommercialContext,
    CustomerContext,
    ExtractedField,
    ExtractedLineItem,
    ExtractionResult,
    ModelMetadata,
    RiskSignals,
    SourceEvidence,
)
from orderpilot_ai_worker.extraction.security.output_sanitizer import sanitize_text, validate_result

# Bound everything that can echo customer content so results/logs never carry unbounded payloads.
MAX_SNIPPET_LEN = 180
MAX_DESCRIPTION_LEN = 180
MAX_SUMMARY_LEN = 240

PROVIDER_NAME = "rule-based-understanding"
MODEL_NAME = "rule-based-v1"
PROMPT_VERSION = "op-cap-12a.prompt.v1"
SCHEMA_VERSION = "stage4.v1"

# A document scoring at/above this bar is advisory-"ready_for_validation"; below it is "needs_review".
# This only routes to deterministic Core API validation — it never approves anything.
READY_CONFIDENCE_FLOOR = 0.5

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
# Lowercase known unit tokens. A quantity unit outside this set is flagged unsupported (advisory) —
# deterministic Core API validation is the real UOM authority.
_KNOWN_UNIT_TOKENS = frozenset(_UOM_NORMALIZE.keys())

# A line item starts with a quantity then a short unit token, optionally prefixed by "line N:".
_LINE_LEAD_QTY = re.compile(r"^\s*(?:line\s*\d+[:.\-\s]+)?(\d{1,5})\s*([A-Za-z]{1,6})\b\s*(.*)$", re.I)

# Advisory commercial hints. Discounts/urgency are flagged only; pricing/margin authority is Core API.
# No trailing \b after the unit: "%" is a non-word char, so "35% discount" has no word boundary
# right after "%". The optional trailing "discount"/"off" is captured when present.
_DISCOUNT = re.compile(r"(\d{1,3}(?:\.\d+)?)\s*(?:%|percent|pct)\s*(?:discount|off)?", re.I)
_DISCOUNT_WORD = re.compile(r"\bdiscount\b", re.I)
_URGENCY_TERMS = ("urgent", "asap", "as soon as possible", "immediately", "today", "rush", "expedite")
# Cyrillic presence is a deterministic, dependency-free language hint (ru/kk markets). Default en.
_CYRILLIC = re.compile(r"[Ѐ-ӿ]")

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
        customer = _build_customer(safe_text, spans, source_channel_context)
        commercial = _build_commercial_context(safe_text, fields)
        risk = _build_risk_signals(fields, line_items, confidence)
        suggestions = _build_suggestions(commercial)
        result = ExtractionResult(
            detected_intent=intent,
            document_type="message",
            overall_confidence=confidence,
            document_confidence=confidence,
            extraction_method="rule_based",
            provider_name=PROVIDER_NAME,
            model_name=MODEL_NAME,
            prompt_version=PROMPT_VERSION,
            schema_version=SCHEMA_VERSION,
            source_channel_context=source_channel_context,
            customer_hints=[spans.customer.group(1).strip()] if spans.customer else [],
            validation_status="ready_for_validation" if _is_ready(confidence, risk) else "needs_review",
            fields=fields,
            line_items=line_items,
            suggestions=suggestions,
            evidence=[_snippet(safe_text, 0, min(len(safe_text), MAX_SNIPPET_LEN))] if safe_text else [],
            language=_detect_language(safe_text),
            customer=customer,
            commercial_context=commercial,
            risk_signals=risk,
            operator_summary=_build_operator_summary(intent, line_items, customer, risk),
            model_metadata=ModelMetadata(
                provider=PROVIDER_NAME, model=MODEL_NAME,
                prompt_version=PROMPT_VERSION, schema_version=SCHEMA_VERSION,
            ),
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
    vehicle = _vehicle_context(text)
    lines = [ln for ln in text.splitlines() if ln.strip()]
    items: List[ExtractedLineItem] = []
    for line in lines:
        item = _line_item_from_line(text, line, len(items) + 1, vehicle)
        if item is not None:
            items.append(item)
    if items:
        return items
    # Fallback: a single document-level item when any business field was found.
    if spans.quantity or spans.sku or _product_text(text):
        sku = spans.sku.group(0) if spans.sku else None
        return [ExtractedLineItem(
            line_number=1,
            raw_sku=sku,
            raw_alias=sku,
            raw_oem_reference=sku,
            raw_description=(_product_text(text) or text.strip())[:MAX_DESCRIPTION_LEN],
            raw_quantity=spans.quantity.group(1) if spans.quantity else None,
            raw_uom=_normalize_uom(spans.quantity.group(2)) if spans.quantity else None,
            requested_date=spans.requested_date.group(1) if spans.requested_date else None,
            ship_to_location_hint=_location_hint(text, spans),
            vehicle_context=vehicle,
            # A free-text single-item RFQ without an explicit SKU is normal (Core API resolves the
            # catalog match); only structured per-line items missing a SKU are flagged ambiguous.
            ambiguous=False,
            confidence=0.58,
            evidence=_snippet(text, 0, min(len(text), MAX_SNIPPET_LEN)),
        )]
    return []


def _line_item_from_line(
    text: str, line: str, line_number: int, vehicle: str | None
) -> ExtractedLineItem | None:
    """Build one advisory line item from a single PO/RFQ line, or None if it is not a line item."""
    qnum, unit_raw, remainder, structured = _parse_qty_lead(line)
    if qnum is None:
        return None
    sku = _SKU.search((remainder or line).upper())
    has_part = _has_part_word(remainder or line)
    unit_known = unit_raw is not None and unit_raw.lower() in _KNOWN_UNIT_TOKENS
    # Reject false positives like "2018 was a good year": a real line item must carry a recognized
    # unit, a SKU candidate, or a known part word.
    if not (unit_known or sku or has_part):
        return None
    sku_token = sku.group(0) if sku else None
    line_offset = text.find(line)
    # Ambiguity is meaningful only for a *structured* PO line (leading quantity) that still lacks a
    # confident SKU candidate. Inline quantities inside prose RFQs are not treated as ambiguous —
    # Core API resolves the catalog match there.
    ambiguous = structured and sku_token is None
    return ExtractedLineItem(
        line_number=line_number,
        raw_sku=sku_token,
        raw_alias=sku_token,
        raw_oem_reference=sku_token,
        raw_description=line.strip()[:MAX_DESCRIPTION_LEN],
        raw_quantity=qnum,
        raw_uom=_normalize_uom(unit_raw) if unit_known else (unit_raw.upper() if unit_raw else None),
        vehicle_context=vehicle,
        ambiguous=ambiguous,
        unsupported_uom=unit_raw is not None and not unit_known,
        confidence=0.6 if sku_token and unit_known else 0.45,
        evidence=_snippet(text, max(0, line_offset), max(0, line_offset) + len(line)),
    )


def _parse_qty_lead(line: str) -> tuple[str | None, str | None, str | None, bool]:
    """Return (quantity, unit_token, remainder, structured).

    ``structured`` is True only for a leading-quantity PO-style line. The legacy inline
    ``<n> x|pcs <sku>`` form (quantity embedded in prose) is preserved but marked non-structured.
    Returns ``(None, None, None, False)`` when no quantity is present.
    """
    lead = _LINE_LEAD_QTY.match(line)
    if lead:
        return lead.group(1), lead.group(2), lead.group(3), True
    inline = _LINE_QTY.search(line)
    if inline:
        return inline.group(1), inline.group(2), line, False
    return None, None, None, False


def _has_part_word(text: str) -> bool:
    lowered = (text or "").lower()
    return any(part in lowered for part in _PARTS_LEXICON)


def _vehicle_context(text: str) -> str | None:
    """Compose a deterministic 'Make Model Year' vehicle hint from vehicle fields, if any."""
    parts = [
        f.normalized_value
        for f in _vehicle_fields(text)
        if f.field_name in ("vehicle_make", "vehicle_model", "vehicle_year") and f.normalized_value
    ]
    if not parts:
        return None
    return " ".join(parts)[:MAX_DESCRIPTION_LEN]


def _document_confidence(fields: List[ExtractedField], intent: str) -> float:
    if not fields:
        return 0.3 if intent != INTENT_UNKNOWN else 0.2
    base = 0.45 + 0.07 * len(fields)
    return round(min(base, 0.92), 3)


def _is_ready(confidence: float, risk: RiskSignals) -> bool:
    """Advisory readiness: confident enough AND no risk flag. Never an approval — only routes to
    deterministic Core API validation."""
    return confidence >= READY_CONFIDENCE_FLOOR and not risk.requires_review


def _detect_language(text: str) -> str:
    """Deterministic, dependency-free language hint. Cyrillic => 'ru', otherwise 'en'."""
    return "ru" if _CYRILLIC.search(text or "") else "en"


def _build_customer(text: str, spans: _Spans, channel: str | None) -> CustomerContext | None:
    name = spans.customer.group(1).strip() if spans.customer else None
    handle = spans.contact.group(0).strip() if spans.contact else None
    if not (name or handle or channel):
        return None
    evidence = None
    if spans.customer:
        evidence = _snippet(text, *spans.customer.span())
    elif spans.contact:
        evidence = _snippet(text, *spans.contact.span())
    confidence = 0.6 if name else (0.5 if handle else 0.3)
    return CustomerContext(
        raw_name=name[:MAX_DESCRIPTION_LEN] if name else None,
        contact_handle=handle[:MAX_DESCRIPTION_LEN] if handle else None,
        channel=channel,
        confidence=confidence,
        evidence=evidence,
    )


def _build_commercial_context(text: str, fields: List[ExtractedField]) -> CommercialContext:
    lowered = text.lower()
    discount = None
    match = _DISCOUNT.search(text)
    if match:
        discount = match.group(0).strip()[:40]
    elif _DISCOUNT_WORD.search(text):
        discount = "discount_requested"
    urgency = "high" if any(term in lowered for term in _URGENCY_TERMS) else None
    if "wholesale" in lowered:
        channel_hint = "wholesale"
    elif "retail" in lowered:
        channel_hint = "retail"
    else:
        channel_hint = None
    location = next(
        (f.normalized_value for f in fields if f.field_name == "ship_to_location_hint"), None
    )
    return CommercialContext(
        requested_discount=discount,
        urgency=urgency,
        wholesale_retail_hint=channel_hint,
        delivery_location_hint=location,
    )


def _build_risk_signals(
    fields: List[ExtractedField], line_items: List[ExtractedLineItem], confidence: float
) -> RiskSignals:
    """Deterministic advisory risk flags. Flags only — never decisions, never actions."""
    details: List[str] = []
    has_quantity = any(f.field_name == "quantity" for f in fields) or any(
        li.raw_quantity for li in line_items
    )
    missing_quantity = bool(line_items) and not has_quantity
    ambiguous = any(li.ambiguous for li in line_items)
    unsupported_uom = any(li.unsupported_uom for li in line_items)
    low_confidence = confidence < READY_CONFIDENCE_FLOOR
    if missing_quantity:
        details.append("missing_quantity")
    if ambiguous:
        details.append("ambiguous_product")
    if unsupported_uom:
        details.append("unsupported_uom")
    if low_confidence:
        details.append("low_confidence")
    return RiskSignals(
        ambiguous_product=ambiguous,
        missing_quantity=missing_quantity,
        unsupported_uom=unsupported_uom,
        low_confidence=low_confidence,
        details=details,
    )


def _build_suggestions(commercial: CommercialContext) -> List[AiSuggestion]:
    """Advisory suggestions that route the document to the right deterministic check. Never a decision.

    A requested discount only *flags* that Core API's deterministic margin/discount guardrail must
    run; the worker never computes margin and never approves the discount.
    """
    suggestions: List[AiSuggestion] = []
    if commercial.requested_discount:
        suggestions.append(AiSuggestion(
            suggestion_type="REQUIRES_MARGIN_VALIDATION",
            suggestion={
                "reason": "discount_requested",
                "handling": "DETERMINISTIC_MARGIN_VALIDATION_REQUIRED",
            },
            confidence=0.9,
        ))
    return suggestions


def _build_operator_summary(
    intent: str,
    line_items: List[ExtractedLineItem],
    customer: CustomerContext | None,
    risk: RiskSignals,
) -> str:
    """One bounded, structured operator-facing line. Uses tokens/counts only — no raw payload echo."""
    who = (customer.raw_name or customer.contact_handle) if customer else None
    parts = [
        f"Intent: {intent}",
        f"{len(line_items)} line item(s)",
        f"customer: {who or 'unknown'}",
    ]
    if risk.requires_review:
        parts.append("risk: " + ", ".join(risk.details or ["review_required"]))
    parts.append("advisory only — deterministic validation required")
    return "; ".join(parts)[:MAX_SUMMARY_LEN]
