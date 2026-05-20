# Stage 10A AI Worker Readiness Audit

## Scope

This audit inspected `apps/ai-worker`, its Dockerfile, tests, README, provider interfaces, schemas, and Docker Compose wiring.

No AI worker code was changed for Stage 10A.

## Summary

The AI worker exists and is currently appropriate for mock/advisory extraction only. It does not require real API keys and does not appear to have a direct business database dependency.

It is not ready for real provider integration or production pilot processing. That is acceptable for Stage 10A because real AI providers are explicitly out of scope.

## Findings

| Question | Answer | Evidence | Readiness |
| --- | --- | --- | --- |
| Does `ai-worker` exist? | Yes | `apps/ai-worker` with Python package, README, Dockerfile, tests. | Ready as skeleton. |
| Does it run in Docker Compose? | Yes | `infra/docker/docker-compose.yml` includes `ai-worker` depending on Redis. | Compose contract exists. |
| Does it have mock extraction? | Yes | `MockTextExtractionProvider`, `MockSemanticExtractionProvider`, `MockLLMProvider`. | Ready for mock-only tests and demos. |
| Does it have schema validation? | Yes | Pydantic schemas use confidence bounds; output sanitizer rejects `advisory_only=false`. | Basic schema validation exists. |
| Does it have provider abstraction? | Yes | Abstract `SemanticExtractionProvider` and `LLMProvider`. | Ready for later provider design, not implementation. |
| Can it write to business tables? | No direct DB write path found | No `psycopg`, `sqlalchemy`, `asyncpg`, database client, or Core API write client found in `apps/ai-worker`. | Good boundary for Stage 10A. |
| Does it have tests? | Yes | `tests/test_stage4_extraction.py`, `test_process_inbound_document.py`, `test_process_processing_job.py`. | Tests exist for advisory behavior. |
| Does it require real API keys? | No | No `openai`, `anthropic`, `azure`, `API_KEY`, `SECRET`, or provider-key dependency found. | Safe for Stage 10A. |

## Current Capabilities

The worker can:

- return advisory extraction output;
- parse simple mock text;
- produce field and line-item schema objects;
- detect prompt-injection phrases;
- sanitize obvious script markers;
- reject outputs that attempt to disable advisory mode;
- reject forbidden business mutation task names in safety tests.

## Current Limitations

The worker does not yet have:

- real OCR integration;
- real LLM provider integration;
- production provider configuration;
- queued Redis worker loop with durable retries;
- Core API callback contract for storing extraction output;
- tenant-aware pilot processing contract;
- correction-learning loop;
- observability for provider latency, token/cost, or confidence drift;
- production secret management;
- provider-level safety/evaluation harness.

## Safety Boundary

The AI worker must remain advisory only:

- no direct business database writes;
- no quote/order/customer/product/inventory/price mutation;
- no ERP/1C/accounting/warehouse writes;
- no customer-facing replies;
- no provider output bypassing Core API validation.

Core API remains the only owner of business truth and controlled mutations.

## Recommended Next Step

Stage 10B should add a mock AI worker pilot skeleton only if needed:

- define a mock shadow-mode job contract;
- add correction/metric payload schemas;
- keep provider output advisory;
- add tests for no business mutations and no real provider keys;
- optionally define Core API callback DTOs behind a mock/local-only contract.

Do not implement real AI provider integration in Stage 10B unless a separate security and secret-management stage is approved first.
