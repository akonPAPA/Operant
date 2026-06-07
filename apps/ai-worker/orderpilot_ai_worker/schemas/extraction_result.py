"""Pydantic schemas for advisory extraction results."""

from typing import List

from pydantic import BaseModel, Field


class ExtractedLineItem(BaseModel):  # pylint: disable=too-few-public-methods
    """Single advisory line item extracted from raw text."""

    raw_text: str
    quantity: float | None = None
    sku: str | None = None
    confidence: float = Field(ge=0.0, le=1.0)


class ExtractionResult(BaseModel):  # pylint: disable=too-few-public-methods
    """Advisory extraction result returned by the worker."""

    document_id: str
    summary: str
    confidence: float = Field(ge=0.0, le=1.0)
    line_items: List[ExtractedLineItem] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
    advisory_only: bool = True
