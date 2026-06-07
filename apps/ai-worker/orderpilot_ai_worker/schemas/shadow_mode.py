"""Pydantic schema for advisory shadow-mode predictions."""

from enum import Enum
from typing import Any, Dict

from pydantic import BaseModel, Field


class SourceType(str, Enum):
    """Allowed source types for advisory shadow-mode predictions."""

    INBOUND_DOCUMENT = "INBOUND_DOCUMENT"
    CHANNEL_MESSAGE = "CHANNEL_MESSAGE"
    DRAFT_QUOTE = "DRAFT_QUOTE"
    DRAFT_ORDER = "DRAFT_ORDER"
    VALIDATION_CASE = "VALIDATION_CASE"


class PredictionType(str, Enum):
    """Allowed prediction categories for advisory shadow-mode output."""

    EXTRACTION = "EXTRACTION"
    VALIDATION = "VALIDATION"
    SUBSTITUTION = "SUBSTITUTION"
    PRICING = "PRICING"
    INVENTORY = "INVENTORY"
    QUOTE_DRAFT = "QUOTE_DRAFT"
    ORDER_DRAFT = "ORDER_DRAFT"


class ProviderMode(str, Enum):
    """Allowed shadow-mode provider execution modes."""

    MOCK_ONLY = "MOCK_ONLY"


class ShadowModeAdvisoryPayload(BaseModel):  # pylint: disable=too-few-public-methods
    """Payload persisted by callers for non-authoritative AI predictions."""

    source_type: SourceType
    source_id: str
    prediction_type: PredictionType
    provider_mode: ProviderMode = ProviderMode.MOCK_ONLY
    provider_label: str = "orderpilot-ai-worker-mock-shadow-v1"
    prediction_payload: Dict[str, Any] = Field(default_factory=dict)
    confidence_score: float = Field(ge=0.0, le=1.0)
    advisory_only: bool = True
    external_writes_enabled: bool = False
