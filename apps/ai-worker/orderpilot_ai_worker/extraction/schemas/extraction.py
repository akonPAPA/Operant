"""Schemas for advisory semantic extraction results."""

from typing import List

from pydantic import BaseModel, Field


class SourceEvidence(BaseModel):  # pylint: disable=too-few-public-methods
    """Safe source evidence pointer for extracted fields."""

    evidence_type: str
    snippet: str | None = None
    start_offset: int | None = None
    end_offset: int | None = None
    page_number: int | None = None
    message_id: str | None = None
    channel_event_id: str | None = None


class ExtractedField(BaseModel):  # pylint: disable=too-few-public-methods
    """Single extracted advisory field with confidence metadata."""

    field_name: str
    raw_value: str | None = None
    normalized_value: str | None = None
    value_type: str = "STRING"
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: SourceEvidence | None = None


class ExtractedLineItem(BaseModel):  # pylint: disable=too-few-public-methods
    """Single advisory RFQ/order line item candidate."""

    line_number: int
    raw_sku: str | None = None
    raw_alias: str | None = None
    raw_description: str | None = None
    raw_quantity: str | None = None
    raw_uom: str | None = None
    requested_date: str | None = None
    ship_to_location_hint: str | None = None
    confidence: float = Field(ge=0.0, le=1.0)
    evidence: SourceEvidence | None = None
    # OP-CAP-12A understanding additions. All optional/defaulted so existing producers/consumers keep
    # working. These are candidate hints only — deterministic Core API validation owns the real
    # SKU/OEM/fitment resolution and never trusts these values.
    raw_oem_reference: str | None = None
    vehicle_context: str | None = None
    ambiguous: bool = False
    unsupported_uom: bool = False


class CustomerContext(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory customer hint (OP-CAP-12A). Never an authoritative customer record."""

    raw_name: str | None = None
    contact_handle: str | None = None
    channel: str | None = None
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)
    evidence: SourceEvidence | None = None


class CommercialContext(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory commercial hints (OP-CAP-12A). Hints only — pricing/margin authority is Core API."""

    requested_discount: str | None = None
    urgency: str | None = None
    wholesale_retail_hint: str | None = None
    delivery_location_hint: str | None = None


class RiskSignals(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory risk signals (OP-CAP-12A). Flags, never decisions or actions.

    These describe what an operator/Core API should scrutinize. None of them approve, block, or
    mutate anything; high risk only forces human review (it cannot become a business command).
    """

    prompt_injection_suspected: bool = False
    ambiguous_product: bool = False
    missing_quantity: bool = False
    low_confidence: bool = False
    unsafe_instruction: bool = False
    possible_data_exfiltration: bool = False
    unsupported_uom: bool = False
    details: List[str] = Field(default_factory=list)

    @property
    def requires_review(self) -> bool:
        """True when any risk signal is set; advisory hint only (Core API decides handling)."""
        return any(
            (
                self.prompt_injection_suspected,
                self.ambiguous_product,
                self.missing_quantity,
                self.low_confidence,
                self.unsafe_instruction,
                self.possible_data_exfiltration,
                self.unsupported_uom,
            )
        )


class ModelMetadata(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory provenance for the producing provider/model (OP-CAP-12A)."""

    provider: str
    model: str
    prompt_version: str
    schema_version: str
    # OP-CAP-12B additive, optional local-runtime provenance. Host is the bounded endpoint
    # host[:port] only (never credentials/keys/auth headers/raw URL); parse_status is a bounded token
    # describing how the model output was parsed. Both default None for all existing producers.
    endpoint_host: str | None = None
    parse_status: str | None = None


class AiSuggestion(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory warning or suggestion attached to extraction output."""

    suggestion_type: str
    suggestion: dict
    confidence: float = Field(ge=0.0, le=1.0)


class ExtractionResult(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory semantic extraction result for validation by core API."""

    detected_intent: str
    document_type: str
    overall_confidence: float = Field(ge=0.0, le=1.0)
    extraction_method: str = "mock"
    provider_name: str = "mock-semantic"
    model_name: str = "mock-rule-based"
    prompt_version: str = "stage4.prompt.v1"
    schema_version: str = "stage4.v1"
    source_channel_context: str | None = None
    customer_hints: List[str] = Field(default_factory=list)
    validation_status: str = "needs_review"
    fields: List[ExtractedField] = Field(default_factory=list)
    line_items: List[ExtractedLineItem] = Field(default_factory=list)
    evidence: List[SourceEvidence] = Field(default_factory=list)
    suggestions: List[AiSuggestion] = Field(default_factory=list)
    advisory_only: bool = True
    # OP-CAP-07B understanding-pipeline additions. All optional / defaulted, so every existing
    # producer and consumer of ExtractionResult keeps working unchanged. They let the pipeline carry
    # source identity, a document-level signal, and safety tags as advisory metadata only.
    extraction_id: str | None = None
    source_type: str | None = None
    source_id: str | None = None
    document_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    warnings: List[str] = Field(default_factory=list)
    prompt_injection_signals: List[str] = Field(default_factory=list)
    # OP-CAP-12A real-understanding structure. All optional/defaulted; the legacy flat fields above
    # (detected_intent, provider_name, schema_version, fields, ...) stay populated for existing
    # consumers, while these expose the richer transaction-understanding view.
    language: str | None = None
    customer: CustomerContext | None = None
    commercial_context: CommercialContext | None = None
    risk_signals: RiskSignals | None = None
    operator_summary: str | None = None
    model_metadata: ModelMetadata | None = None
    # Source key of an OP-CAP-11I scripted fixture when the input was recognized as one. Advisory
    # provenance only; never trusted as auth or used to bypass validation.
    fixture_source_key: str | None = None
