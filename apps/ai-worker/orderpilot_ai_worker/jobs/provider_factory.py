"""Extraction provider factory: ProviderMode -> constructed advisory provider (OP-CAP-12C).

A single fail-closed selection point that wires the existing 12A/12B providers into job orchestration:
the deterministic rule-based default, the mock semantic provider, and the local open-source
Ollama-compatible runtime. The default stays deterministic and **offline**. ``LOCAL_OLLAMA`` runs only
when explicitly enabled with an endpoint + model, and even then a transport must be present
(production injects :func:`build_urllib_transport`; tests inject a fake). There is no paid API, no key,
and no network call by default — a misconfigured local mode fails closed rather than reaching out.

This factory only *constructs* a provider; it adds no business-validation, catalog/price/inventory,
approval, or mutation path. Model output remains advisory and is re-validated by the same schema as
every other provider, and Core API deterministic validation stays authoritative.
"""

from dataclasses import dataclass
from typing import Optional

from orderpilot_ai_worker.extraction.providers.local_ollama import (
    RUNTIME_NAME,
    LocalModelConfig,
    LocalModelTransport,
    LocalOllamaExtractionProvider,
    build_urllib_transport,
)
from orderpilot_ai_worker.extraction.providers.rule_based import RuleBasedExtractionProvider
from orderpilot_ai_worker.extraction.providers.semantic_extraction import (
    MockSemanticExtractionProvider,
    SemanticExtractionProvider,
)
from orderpilot_ai_worker.jobs.models import ProviderMode


class ProviderResolutionError(RuntimeError):
    """Raised when a provider mode cannot be resolved to a runnable provider (fail-closed).

    Carries a bounded, safe ``reason`` token only — never config, endpoint, model name, key, or
    customer text.
    """

    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


@dataclass
class ResolvedProvider:
    """A constructed advisory provider plus its safe provenance for job-result metadata."""

    provider: SemanticExtractionProvider
    provider_name: str
    provider_version: str
    mode: ProviderMode


# Simple, no-arg deterministic providers (offline, no config, no transport). Kept as a mutable dict so
# existing job tests can monkeypatch a single mode in place (see jobs.handler, which aliases this as
# ``_PROVIDERS``). The local runtime is resolved separately because it needs config + a transport.
_SIMPLE_PROVIDERS: dict[ProviderMode, tuple[type[SemanticExtractionProvider], str, str]] = {
    ProviderMode.RULE_BASED: (RuleBasedExtractionProvider, "rule-based-understanding", "rule-based-v1"),
    ProviderMode.MOCK_SEMANTIC: (MockSemanticExtractionProvider, "mock-semantic", "mock-v1"),
}


def build_extraction_provider(
    mode: ProviderMode,
    *,
    local_config: Optional[LocalModelConfig] = None,
    local_transport: Optional[LocalModelTransport] = None,
) -> ResolvedProvider:
    """Resolve a provider mode to a constructed advisory provider. Fail-closed on unknown modes.

    ``local_config`` / ``local_transport`` let tests (and explicit callers) inject a fake local config
    and transport so the ``LOCAL_OLLAMA`` path is exercised with no real network and no installed
    model. They are ignored for the deterministic modes.
    """
    simple = _SIMPLE_PROVIDERS.get(mode)
    if simple is not None:
        provider_cls, name, version = simple
        return ResolvedProvider(provider_cls(), name, version, mode)
    if mode is ProviderMode.LOCAL_OLLAMA:
        return _build_local_ollama(local_config, local_transport)
    # FUTURE_SEMANTIC and any unmapped/unknown mode: refuse rather than guess or reach any network.
    raise ProviderResolutionError("unsupported_provider_mode")


def _build_local_ollama(
    config: Optional[LocalModelConfig], transport: Optional[LocalModelTransport]
) -> ResolvedProvider:
    """Construct the local Ollama provider from config, building a transport only when ready.

    A real (urllib) transport is constructed **only** when the local runtime is explicitly enabled and
    fully configured (enabled + endpoint + model). Otherwise the transport stays ``None`` and the
    provider fails closed (``ProviderDisabledError``) at extract time without ever building a network
    client. The pipeline turns that into a controlled advisory failure result.
    """
    cfg = config if config is not None else LocalModelConfig.from_env()
    resolved_transport = transport
    if resolved_transport is None and cfg.is_ready:
        resolved_transport = build_urllib_transport()
    provider = LocalOllamaExtractionProvider(config=cfg, transport=resolved_transport)
    return ResolvedProvider(
        provider=provider,
        provider_name=RUNTIME_NAME,
        # The configured (operator-chosen) model name is safe, non-secret provenance; never hardcoded.
        provider_version=cfg.model or "unconfigured",
        mode=ProviderMode.LOCAL_OLLAMA,
    )
