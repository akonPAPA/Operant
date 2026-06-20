"""AI-worker provider evaluation harness (OP-CAP-12D).

An offline, fixture-driven regression and safety harness for the advisory extraction providers. It
reuses the existing provider interfaces, the existing ``SemanticExtractionPipeline``, and the existing
``ExtractionResult`` schema — it does NOT introduce a second extraction pipeline, a business-write
path, paid APIs, real network, or a real Ollama dependency. See ``docs/ai/AI_EVALUATION_HARNESS.md``.
"""

from orderpilot_ai_worker.evaluation.evaluator import (
    evaluate_case,
    evaluate_cases,
    evaluation_report,
    run_default_evaluation,
    summarize,
    write_evaluation_report,
)
from orderpilot_ai_worker.evaluation.models import (
    EvaluationCase,
    EvaluationFinding,
    EvaluationResult,
    EvaluationSummary,
    ExpectedExtraction,
)
from orderpilot_ai_worker.evaluation.cases import default_evaluation_cases

__all__ = [
    "EvaluationCase",
    "ExpectedExtraction",
    "EvaluationFinding",
    "EvaluationResult",
    "EvaluationSummary",
    "evaluate_case",
    "evaluate_cases",
    "evaluation_report",
    "summarize",
    "run_default_evaluation",
    "write_evaluation_report",
    "default_evaluation_cases",
]
