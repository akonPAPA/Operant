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
