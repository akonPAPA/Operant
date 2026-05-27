# OrderPilot Core v1 AI Development Roadmap

This file is the canonical roadmap and instruction source for OrderPilot Core v1 AI-assisted development. `AGENTS.md`, local task prompts, and stage docs must align with this file.

## Product Thesis

OrderPilot Core v1 is a controlled B2B transaction intelligence platform for auto and industrial parts distributors. It turns inbound customer demand from messages, documents, files, and channel integrations into validated quote/order work without giving AI, chatbots, frontend clients, or connectors direct authority over business records.

The product thesis is trust before autonomy: AI can understand and suggest, but deterministic backend services decide. Every risky business mutation must be tenant-scoped, validated, approval-gated where required, audited, and routed through typed command services.

## Non-Negotiable Rules

- AI output is advisory only.
- AI, chatbot, frontend, worker, and connector code must not directly write master business data.
- Do not add AI-to-database, bot-to-database, frontend-to-master-data, or connector-to-master-data direct write paths.
- All business mutations must go through typed Core API backend command services.
- Every tenant-owned read or write must be tenant-scoped.
- Risky actions require deterministic validation, tenant policy enforcement, approval where required, database transactions, and audit events.
- Do not create duplicate architecture, tables, repositories, or service paths when equivalent domain models already exist.
- No ERP, external system, connector command, inventory reservation, price change, customer change, product change, quote approval, or order approval may be added unless the active phase explicitly allows it.
- Preserve the existing security model, permission guardrails, tenant isolation, audit behavior, and approval workflow.

## Core Write Path

Authoritative business changes must follow this path:

1. Tenant and actor context are established.
2. Typed backend command service receives the request.
3. Tenant policy and permissions are evaluated.
4. Deterministic validation checks product, customer, UOM, inventory, pricing, discount, margin, substitution, and approval requirements as applicable.
5. Required approvals are collected before risky state changes proceed.
6. Changes occur inside a transaction.
7. Audit events record important decisions, blocked attempts, approvals, rejections, and mutations.
8. External writes use explicit connector/change-request stages only.

## Phase Roadmap

### Phase 1: Platform Foundation

Establish the Core API, persistence baseline, tenant context, core project structure, and local verification workflow.

### Phase 2: Data Foundation

Model tenant-owned operational data such as customers, products, product aliases, OEM references, compatibility, substitutes, locations, inventory snapshots, price rules, discount rules, and margin rules.

### Phase 3: Omnichannel Intake

Completed. Inbound documents, attachments, channel messages, processing jobs, webhook handling, and intake validation exist as tenant-owned records. Intake stores source material and processing state, but does not create final quotes/orders or mutate master data directly.

### Phase 4: AI-Assisted Understanding Pipeline

Completed. The system stores advisory AI extraction runs, extraction results, fields, line items, suggestions, source evidence, confidence, and processing status. AI output remains untrusted advisory data and cannot create final business records.

### Phase 5A: Validation/Substitution/Pricing Intelligence

Completed. Advisory extraction results are bridged into deterministic validation outcomes. The validation engine checks SKU, aliases, OEM references, UOM normalization, inventory availability, price rules, discount rules, margin guardrails, substitute candidates, confidence, issues, approval requirements, and routing recommendations.

Validation may create validation runs, validation results, issues, substitute candidates, approval requirements, and audit events. It must not create approved quotes/orders, reserve inventory, change pricing, mutate customer/product master data, or write to ERP.

### Phase 5B: Validation-To-Review Bridge

Completed. Validation outcomes can be converted into operator-reviewable cases and then into internal draft quote/order command preparation after review gates pass.

The bridge reuses the existing review/exception workflow and draft quote/order services. It blocks unresolved unknown UOM, unresolved ambiguous or missing product matches, blocked substitutes, discount/margin approval risks before review approval, and low-confidence extraction cases before review approval.

Draft preparation is internal only. It does not approve quotes/orders, reserve inventory, create connector commands, or write to ERP/external systems.

### Phase 6A: Quote/Order Workspace UI And Command Flow

Completed. Build the operator quote/order workspace and command flow on top of existing review and draft command services. The UI may display validation, issues, approvals, suggested actions, substitute candidates, and draft records. It must not bypass backend command services, tenant policy, validation, approvals, or audit.

Phase 6A may support operator actions such as reviewing cases, requesting correction, approving for draft, preparing internal drafts, and moving drafts through internal workflow states. ERP/external writes remain out of scope unless a later connector/write phase explicitly enables them.

The Phase 6A workspace must display backend rejection reasons, blocked substitutes, unknown UOM, ambiguous product matches, margin/discount approval risks, low-confidence extraction signals, and open approval requirements. Draft quote/order preparation remains internal only.

### Phase 6B: Validation Review Correction And Override Workflow

Completed. Add tenant-scoped backend correction and override commands so operators can resolve validation review risks before draft quote/order preparation. Corrections may update review/validation state for extracted UOM, quantity, product mapping, substitute decisions, issue acknowledgment, and reasoned override. They must be audited and must not mutate product catalog, inventory, pricing, customer master data, connector commands, ERP state, or external systems.

Draft quote/order preparation remains blocked while unresolved blocking issues, blocked substitutes, ambiguous product matches, or open approval requirements remain.

### Phase 6C: Validation Review Workspace Ergonomics, Candidate Selection, Approval Visibility And Audit Timeline

Completed. Improve the operator validation review workspace so review details expose issue lifecycle, product candidates, substitute risk cues, pending approvals, correction history, blocking reasons, and safe internal draft previews. Draft preview must not create approved quotes/orders, reserve inventory, execute connector commands, or write to ERP/external systems.

### Phase 6D: Manager Approval Decisions And Final Draft Readiness Gate

Completed. Add manager approval decision handling and a single backend-authoritative draft readiness evaluator for validation review cases. Review detail, draft preview, draft quote preparation, and draft order preparation must use the same readiness result.

Phase 6D may approve or reject existing approval requirements through tenant-scoped backend commands with reason capture and audit events. It must keep blocked substitutes, rejected required approvals, pending approvals, unresolved product/UOM/quantity corrections, risky substitutes, discount exceptions, margin guardrail violations, and unapproved review states from reaching draft preparation.

Draft readiness remains internal only. Phase 6D must not approve quotes/orders, reserve inventory, execute connector commands, or write to ERP/external systems.

### Phase 6E: Validation Review Workspace Final Acceptance Sweep

Completed. Close the Phase 6 review/draft/approval cockpit by verifying consistency across review detail, draft preview, draft quote preparation, draft order preparation, frontend display, and documentation.

Phase 6E may clean stale docs, inconsistent labels, and brittle acceptance tests. It must not add product features, database tables, ERP writes, connector execution, bot runtime work, or frontend/direct business-table write paths.

### Phase 7A: Bot Runtime Lite Foundation

Completed. Add a safe Telegram-oriented bot runtime foundation that can capture customer messages, classify a bounded business intent, create RFQ request draft and human handoff records, and expose operator visibility.

The bot runtime must not approve quotes/orders, approve discounts, approve substitutes, reserve inventory, mutate inventory/pricing/product/customer master data, execute connectors, or write to ERP. Customer message text is hostile input and cannot override backend policy.

### Phase 7B: Telegram Webhook Operationalization

Completed. Accept real Telegram-style inbound webhook payloads through the bot runtime, verify configured secret-token policy or explicit local/demo fixture mode, normalize supported text updates into channel and bot records, and route through deterministic classification, bot policy, handoff, and audit.

Phase 7B must not add real Telegram tokens, outbound send-message behavior, LLM calls, connector execution, inventory reservation, approvals, final quote/order creation, or ERP writes.

### Phase 7C: Operator-Assisted Safe Bot Responses

Completed. Prepare bounded Telegram-oriented response drafts through deterministic templates, `BotPolicyService`, and operator-controlled review/stub-send commands. Stub-send records local simulated send state through a no-op transport only.

Phase 7C must not add real outbound Telegram network calls, LLM/free-form response generation, real bot secrets, unrestricted product/customer/price disclosure, connector execution, inventory reservation, approvals, final quote/order creation, or ERP writes. Response draft creation, ready marking, blocked decisions, handoff-required states, and stub-send events remain tenant-scoped and audit logged.

### Phase 7D: Conversation-to-Review Handoff Hardening

Completed. Link bot conversations, RFQ requests, messages, and handoff reasons into existing operator review/exception handling. Bot-originated review cases preserve Telegram/BOT source context and give operators a queue target without making the conversation draft-ready.

Phase 7D must not bypass Phase 6 readiness, validation correction, manager approval, or draft preparation gates. It must not add real Telegram outbound sends, real bot secrets, LLM/free-form generation, connector execution, inventory reservation, approvals, final quote/order creation, or ERP writes.

### Phase 7E: Bot Runtime Final Acceptance

Completed. Harden operator queue routing so bot-originated `ExceptionCase` records are clearly treated as bot operator handoffs, not validation-backed readiness cases. Bot handoff detail exposes source metadata, `sourceConversationId`, latest message, policy decision, handoff reason, and descriptive next actions while validation review detail rejects bot-only cases unless they are explicitly converted in a later phase.

Phase 7E closes the safe bot runtime boundary. Bot-only handoffs are not draft-ready, and draft quote/order preparation remains blocked by backend readiness gates. Real outbound Telegram transport and bot RFQ conversion into validation-backed quote/order workflow remain separate future work requiring security, runbook, and operator authorization acceptance.

### Stage 8A: Commerce Intelligence Foundation

Completed. Add the first read-only commerce intelligence layer for command-center metrics over existing workflow data: request volume by channel, automation rate, exception rate, validation-backed review count, bot-only handoff count, blocked unsafe draft attempts, draft quote/order preparation count, review cycle time, and discount/margin risk counts where validation data supports them.

Stage 8A must not add real Telegram outbound sends, LLM calls, ERP writes, connector commands, inventory reservation, or changes to Phase 6 validation-backed review behavior. Bot-only handoffs remain separate from validation-backed reviews.

### Stage 8B: Reconciliation Foundation And Analytics Read Model Hardening

Completed. Add deterministic expected-vs-actual inventory reconciliation using mirrored movement records, reconciliation cases, stale inventory warnings, likely-cause labels, product-level movement timelines, and inventory analytics cards. Add indexed read-model query support for Stage 8 dashboard metrics without introducing a new BI stack.

Stage 8B must not mutate real inventory snapshots, reserve inventory, create connector commands, write to ERP, call Telegram outbound APIs, add LLM calls, or change Phase 6 validation-backed review behavior. Reconciliation refresh may create or update internal reconciliation cases and audit events only.

### Stage 8C: Business Value Dashboard And Pilot ROI Layer

Completed. Add ROI-ready business value analytics over existing workflow records: estimated operator hours saved, estimated labor cost saved, review and draft-preparation cycle time, exception causes, unsafe attempts blocked, discount leakage, margin risk, substitute recovered revenue indicators, inventory discrepancy indicators, stale inventory risk, configurable tenant ROI assumptions, and exportable pilot ROI report payloads.

Stage 8C must not add real Telegram outbound sends, LLM calls, ERP writes, connector commands, inventory reservation, inventory mutation, external system mutation, or changes to Phase 6 validation-backed review behavior. Bot-only handoffs remain separate from validation-backed reviews. Draft quote/order values and substitute line values are estimated pilot indicators only and must not be treated as closed revenue unless a later phase adds an explicit accepted/closed revenue model.

### Stage 9A: Integration Control Foundation And Demo ERP Adapter

Completed. Add the first controlled integration layer connecting approved internal workflow to a demo ERP adapter through tenant-scoped `ChangeRequest` records, integration connections, connector sync/audit events, and deterministic demo external references.

Stage 9A may execute only through an in-process Demo ERP adapter. It must not add production ERP/1C writes, real external network calls, real secrets, connector commands from bot-only handoffs, connector commands from non-validation-backed cases, inventory reservation, inventory mutation, Telegram outbound sends, LLM calls, microservices, Kafka, ClickHouse, or new infrastructure. Bot-only handoffs remain ineligible for connector ChangeRequests. Approved validation-backed draft quote/order records are the only eligible Stage 9A sources.

### Stage 9B: Connector Safety, Idempotency And Runbook Hardening

Completed. Harden the connector foundation for future production readiness without enabling real writes. Add explicit demo-only execution policy, connector capabilities, credential placeholders, idempotency replay handling, manual retry/cancel semantics, failure metadata, connector audit visibility, and connector safety/runbook documentation.

Stage 9B must remain demo/local-only. It must not add real ERP/1C writes, external network calls, real secret handling, secret storage, inventory reservation, inventory mutation, Telegram outbound sends, LLM calls, microservices, Kafka, ClickHouse, or new infrastructure. Production connector activation requires separate security and runbook acceptance.

Stage 9B verification note: sandboxed Maven dependency resolution can be blocked by network restrictions while resolving Maven Central artifacts. In the current review pass, the targeted Stage 9B backend suite passed after approved Maven execution, and frontend `npm.cmd run lint`, `npm.cmd run test`, and `npm.cmd run build` passed. If approved Maven dependency resolution is unavailable in a future run, backend verification must be marked blocked rather than accepted.

### Stage 10: Security, Reliability And Investor Demo Hardening

Current. Close targeted Stage 9B demo-only integration-control safety and focus on full verification, security evidence pack, reliability/runbooks, investor demo scripts, UAT checklist, and demo/pilot readiness.

Stage 10 does not add production connectors, real ERP/1C writes, external connector network calls, raw secret handling, inventory mutation, bot-triggered connector commands, LLM calls, or new infrastructure. It preserves `ChangeRequest` as the integration lifecycle source, `connectorIdempotencyKeyHash` as the API/frontend field, `sha256:*` idempotency values, replay audit metadata, demo-only execution mode, and policy-block audit events.

Stage 10 verification result: sandboxed backend `mvn test` was blocked by Maven Central dependency/network restrictions; approved Maven `mvn test` passed with 312 tests, 0 failures, 0 errors. Frontend `npm.cmd run lint`, `npm.cmd run test` (39 tests), and `npm.cmd run build` passed. AI worker `.venv\Scripts\python.exe -m pytest` passed with 12 tests.

## Agent Working Rules

- Before each task, inspect existing code, migrations, docs, and tests.
- Reuse existing models and services before adding new ones.
- Keep changes scoped to the requested phase.
- Do not modify unrelated dirty worktree files.
- Do not weaken security, tenant isolation, validation, approvals, or audit to make tests pass.
- Do not implement future phases early.
- Every task response should include assumptions, changed files, tests run, risks/blockers, and the next recommended step.
