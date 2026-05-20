from abc import ABC, abstractmethod


class LLMProvider(ABC):
    @abstractmethod
    def extract_business_intent(self, text: str) -> dict:
        """Return advisory structured extraction. Core API must validate all output."""


class MockLLMProvider(LLMProvider):
    def extract_business_intent(self, text: str) -> dict:
        return {
            "document_id": "mock",
            "summary": text[:160],
            "confidence": 0.5,
            "line_items": [],
            "warnings": ["mock_provider_output_not_authoritative"],
        }