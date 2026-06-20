"""Stage 39D opt-in local-runtime adversarial matrix.

This test is skipped by default so CI remains deterministic and offline. When explicitly enabled, it
exercises the LOCAL_OLLAMA path with the existing injected fake transport fixtures. The real Ollama
manual run remains documented in the Stage 39D runbook; this matrix proves the local-runtime contract
without depending on model availability or stochastic output.
"""

import os

import pytest

from orderpilot_ai_worker.evaluation import default_evaluation_cases, evaluate_cases
from orderpilot_ai_worker.jobs.models import ProviderMode

pytestmark = pytest.mark.skipif(
    os.getenv("ORDERPILOT_STAGE39D_LOCAL_ADVERSARIAL") != "1",
    reason="Stage 39D local adversarial matrix is opt-in only",
)


def test_stage39d_opt_in_local_ollama_adversarial_matrix_is_advisory_and_fail_closed() -> None:
    required_case_ids = {
        "local_valid_response",
        "local_ambiguous_requires_review",
        "local_prompt_injection_guarded",
        "local_business_action_surface_rejected",
        "local_invalid_json_rejected",
        "local_schema_invalid_response",
    }
    required_categories = {
        "normal_rfq",
        "ambiguous_rfq",
        "prompt_injection",
        "unsafe_model_output",
        "malformed_model_output",
    }
    cases = [
        case
        for case in default_evaluation_cases()
        if case.provider_mode is ProviderMode.LOCAL_OLLAMA and case.case_id in required_case_ids
    ]

    summary = evaluate_cases(cases)

    assert {case.case_id for case in cases} == required_case_ids
    assert required_categories.issubset({case.category for case in cases})
    assert summary.all_passed is True
    assert summary.unsafe_partial_data_violations == 0
    by_case = {result.case_id: result for result in summary.results}
    assert by_case["local_valid_response"].advisory_only is True
    assert by_case["local_ambiguous_requires_review"].validation_status == "needs_review"
    assert by_case["local_ambiguous_requires_review"].overall_confidence <= 0.5
    assert by_case["local_prompt_injection_guarded"].prompt_injection_detected is True
    assert by_case["local_prompt_injection_guarded"].validation_status == "needs_review"
    for case_id in required_case_ids - {
        "local_valid_response",
        "local_ambiguous_requires_review",
        "local_prompt_injection_guarded",
    }:
        assert by_case[case_id].failure_category is not None
        assert by_case[case_id].line_item_count == 0
        assert by_case[case_id].unsafe_partial_business_data is False
