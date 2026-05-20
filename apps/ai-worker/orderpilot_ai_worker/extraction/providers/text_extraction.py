from abc import ABC, abstractmethod


class TextExtractionProvider(ABC):
    @abstractmethod
    def extract_text(self, payload: dict) -> str:
        """Return text only. Do not mutate business data."""


class MockTextExtractionProvider(TextExtractionProvider):
    def extract_text(self, payload: dict) -> str:
        return str(payload.get("text") or payload.get("metadata") or "Mock extracted text: Need 10 EA SKU-001")