"""Pydantic schema for advisory shadow-mode predictions."""

from typing import Any, Literal

from pydantic import BaseModel, Field


class ShadowModeAdvisoryPayload(BaseModel):  # pylint: disable=too-few-public-methods
    """Payload persisted by callers for non-authoritative AI predictions."""

    source_type: Literal[
        "INBOUND_DOCUMENT",
        "CHANNEL_MESSAGE",
        "DRAFT_QUOTE",
        "DRAFT_ORDER",
        "VALIDATION_CASE",
    ]
    source_id: str
    prediction_type: Literal[
        "EXTRACTION",
        "VALIDATION",
        "SUBSTITUTION",
        "PRICING",
        "INVENTORY",
        "QUOTE_DRAFT",
        "ORDER_DRAFT",
    ]
    provider_mode: Literal["MOCK_ONLY"] = "MOCK_ONLY"
    provider_label: str = "orderpilot-ai-worker-mock-shadow-v1"
    prediction_payload: dict[str, Any] = Field(default_factory=dict)
    confidence_score: float = Field(ge=0.0, le=1.0)
    advisory_only: bool = True
    external_writes_enabled: bool = False
