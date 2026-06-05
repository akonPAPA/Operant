"""LLM provider contract for advisory worker output."""

from abc import ABC, abstractmethod


class LLMProvider(ABC):  # pylint: disable=too-few-public-methods
    """Provider boundary for advisory business-intent extraction."""

    @abstractmethod
    def extract_business_intent(self, text: str) -> dict:
        """Return advisory structured extraction. Core API must validate all output."""


class MockLLMProvider(LLMProvider):  # pylint: disable=too-few-public-methods
    """Deterministic mock provider that never writes trusted business data."""

    def extract_business_intent(self, text: str) -> dict:
        """Return a fixed advisory result shape for local processing tests."""
        return {
            "document_id": "mock",
            "summary": text[:160],
            "confidence": 0.5,
            "line_items": [],
            "warnings": ["mock_provider_output_not_authoritative"],
        }
