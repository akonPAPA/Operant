"""Typed domain models for the AI-worker provider evaluation harness (OP-CAP-12D).

These models describe *cases*, *findings*, and *summaries* only. They carry no business authority and
no executable surface: every assertion here is about advisory extraction behavior (schema validity,
advisory_only flag, confidence, fail-closed routing, prompt-injection guarding), never about real
SKU/price/customer/stock truth — that stays with deterministic Core API validation.
"""

from typing import List, Optional

from pydantic import BaseModel, Field

from orderpilot_ai_worker.jobs.models import ProviderMode


class ExpectedExtraction(BaseModel):  # pylint: disable=too-few-public-methods
    """What a case asserts about provider behavior. All advisory/structural — never business truth.

    Every expectation is optional/opt-in so a case only checks what it means to check. Universal safety
    invariants (schema-valid, advisory_only, no unsafe partial data, no executable surface) are always
    enforced by the evaluator regardless of what is set here.
    """

    should_succeed: bool = False
    expect_controlled_failure: bool = False
    expect_prompt_injection: bool = False
    expect_resolution_error: bool = False
    # Local-runtime only. None = not checked; True/False = transport must / must not be invoked.
    expect_transport_called: Optional[bool] = None
    expected_intent: Optional[str] = None
    expected_validation_status: Optional[str] = None
    expect_line_items: Optional[bool] = None
    expect_review_or_low_confidence: bool = False
    max_confidence: Optional[float] = None


class EvaluationCase(BaseModel):  # pylint: disable=too-few-public-methods
    """One offline evaluation case: an input + the provider mode to run it through + expectations.

    Local-runtime knobs are plain data; the evaluator builds a ``LocalModelConfig`` and an in-process
    fake transport from them. No real network, no installed model, no paid key is ever involved.
    """

    case_id: str
    category: str = "general"
    description: str = ""
    source_type: str = "message"  # message | document | pdf | email | ...
    raw_text: Optional[str] = None
    provider_mode: ProviderMode = ProviderMode.RULE_BASED
    local_enabled: bool = False
    local_endpoint: Optional[str] = None
    local_model: Optional[str] = None
    # Body the fake local transport returns (an Ollama envelope or direct JSON). Never sent anywhere.
    local_response_body: Optional[str] = None
    expected: ExpectedExtraction = Field(default_factory=ExpectedExtraction)


class EvaluationFinding(BaseModel):  # pylint: disable=too-few-public-methods
    """A single deterministic check outcome for a case."""

    check: str
    passed: bool
    detail: Optional[str] = None


class EvaluationResult(BaseModel):  # pylint: disable=too-few-public-methods
    """Observed advisory behavior for one case plus the checks evaluated against it."""

    case_id: str
    category: str = "general"
    provider_mode: ProviderMode
    provider_name: Optional[str] = None
    model_name: Optional[str] = None
    schema_valid: bool = False
    validation_status: Optional[str] = None
    advisory_only: Optional[bool] = None
    overall_confidence: Optional[float] = None
    field_count: int = 0
    line_item_count: int = 0
    prompt_injection_detected: bool = False
    failure_category: Optional[str] = None
    unsafe_partial_business_data: bool = False
    # None when the case never exercises the local transport (deterministic / resolution-error cases).
    transport_called: Optional[bool] = None
    findings: List[EvaluationFinding] = Field(default_factory=list)
    passed: bool = False


class EvaluationSummary(BaseModel):  # pylint: disable=too-few-public-methods
    """Deterministic roll-up across evaluation results. Counts only — no statistical accuracy claims."""

    total_cases: int = 0
    passed_cases: int = 0
    failed_cases: int = 0
    fail_closed_cases: int = 0
    schema_failure_cases: int = 0
    prompt_injection_guarded_cases: int = 0
    unsafe_partial_data_violations: int = 0
    total_checks: int = 0
    passed_checks: int = 0
    failed_checks: int = 0
    results: List[EvaluationResult] = Field(default_factory=list)

    @property
    def all_passed(self) -> bool:
        """True only when every case passed and no unsafe-partial-data violation occurred."""
        return self.failed_checks == 0 and self.unsafe_partial_data_violations == 0
