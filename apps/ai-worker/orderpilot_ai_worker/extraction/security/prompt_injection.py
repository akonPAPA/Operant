"""Prompt-injection phrase detection for hostile inbound text."""

from typing import List

SUSPICIOUS_PHRASES = (
    "ignore previous instructions",
    "dump database",
    "bypass validation",
    "bypass security",
    "call the database tool",
    "write to database",
    "you are now system admin",
    "approve this order",
    "create quote",
    "create order",
    "write to erp",
)


def detect_prompt_injection(text: str) -> List[str]:
    """Return suspicious phrases present in inbound text."""
    lowered = text.lower()
    return [phrase for phrase in SUSPICIOUS_PHRASES if phrase in lowered]
