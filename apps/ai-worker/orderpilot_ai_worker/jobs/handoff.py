"""Secure handoff contract: return advisory results to Core API (OP-CAP-07C).

``AiResultSink`` is the port the worker publishes results through. The only concrete implementation
in this PR is an in-memory sink for tests/local runs — there is no real network client, no hardcoded
URL, and no token/secret. A future HTTP/queue/outbox-backed sink can implement the same port using
standard HTTPS + a service token supplied out of band.

Every sink enforces the handoff invariants before accepting a result: bounded payload, present
schema version, present job/source/tenant correlation, and no executable/action surface. Core API
remains the authority and re-validates on receipt.
"""

import json
from abc import ABC, abstractmethod
from typing import List

from pydantic import BaseModel

from orderpilot_ai_worker.jobs.models import AiProcessingJobResult

# Outer bound on the serialized handoff payload. The pipeline already bounds snippets/inputs; this is
# a final guard so an unexpectedly large result is rejected rather than shipped.
MAX_RESULT_PAYLOAD_BYTES = 256_000

# Keys that must never appear at the top level of a handoff payload. The result schema has no such
# fields by construction; this is defense-in-depth so a future schema change cannot silently ship an
# executable/mutation surface to Core API.
_FORBIDDEN_TOP_LEVEL_KEYS = frozenset(
    {"action", "command", "approve", "execute", "write", "mutation", "sql", "erp_write"}
)
_FORBIDDEN_NESTED_KEYS = frozenset(
    {
        "action",
        "command",
        "approve",
        "execute",
        "write",
        "mutation",
        "sql",
        "erpwrite",
        "inventoryupdate",
        "priceupdate",
        "customerupdate",
        "ordercreate",
        "createorder",
        "quotecreate",
        "createquote",
        "quoteapprove",
        "tenantid",
        "actorid",
        "userid",
        "permissions",
        "permission",
        "status",
        "approval",
        "approvalstatus",
        "execution",
        "executionstatus",
        "connector",
        "connectorcommand",
        "erp",
        "erpcommand",
        "onec",
        "1c",
        "externalwrite",
        "writecommand",
        "toolcall",
        "functioncall",
    }
)
_ALLOWED_PIPELINES = frozenset({"RULE_BASED", "MOCK_SEMANTIC", "LOCAL_OLLAMA"})


class HandoffRejected(Exception):
    """Raised when a result fails a handoff invariant. ``reason`` is a bounded, safe token."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


class PublishOutcome(BaseModel):  # pylint: disable=too-few-public-methods
    """Bounded, safe acknowledgement returned by a sink."""

    accepted: bool
    job_id: str
    reason: str | None = None


class AiResultSink(ABC):
    """Port for publishing an advisory job result back to Core API."""

    @abstractmethod
    def publish_result(self, result: AiProcessingJobResult) -> PublishOutcome:
        """Publish one advisory result. Implementations must call :func:`assert_handoff_safe`."""


class InMemoryResultSink(AiResultSink):
    """Non-network sink that captures published results for tests/local runs."""

    def __init__(self) -> None:
        self.published: List[AiProcessingJobResult] = []

    def publish_result(self, result: AiProcessingJobResult) -> PublishOutcome:
        assert_handoff_safe(result)
        self.published.append(result)
        return PublishOutcome(accepted=True, job_id=result.job_id)


def assert_handoff_safe(result: AiProcessingJobResult) -> None:
    """Enforce the handoff invariants. Raises ``HandoffRejected`` with a safe reason on violation."""
    if not (result.job_id or "").strip():
        raise HandoffRejected("missing_job_id")
    if not (result.source_id or "").strip():
        raise HandoffRejected("missing_source_id")
    if not (result.tenant_ref or "").strip():
        raise HandoffRejected("missing_tenant_ref")
    if not (result.schema_version or "").strip():
        raise HandoffRejected("missing_schema_version")

    payload = result.model_dump(mode="json")
    forbidden = _FORBIDDEN_TOP_LEVEL_KEYS.intersection(payload)
    if forbidden:
        raise HandoffRejected("forbidden_action_surface")
    if result.provider_metadata is not None and result.provider_metadata.mode.value not in _ALLOWED_PIPELINES:
        raise HandoffRejected("unsupported_pipeline")
    if result.extraction_result is not None and result.extraction_result.advisory_only is not True:
        raise HandoffRejected("non_advisory_result")
    _assert_no_unsafe_nested_keys(payload.get("extraction_result"))

    encoded = json.dumps(payload).encode("utf-8")
    if len(encoded) > MAX_RESULT_PAYLOAD_BYTES:
        raise HandoffRejected("payload_too_large")


def _assert_no_unsafe_nested_keys(value: object) -> None:
    if isinstance(value, dict):
        for key, nested in value.items():
            normalized = "".join(ch for ch in str(key) if ch.isalnum()).lower()
            if normalized in _FORBIDDEN_NESTED_KEYS:
                raise HandoffRejected("forbidden_action_surface")
            _assert_no_unsafe_nested_keys(nested)
        return
    if isinstance(value, list):
        for item in value:
            _assert_no_unsafe_nested_keys(item)
