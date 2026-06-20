# OP-CAP Roadmap Sync Lock

Single small sync-lock for the OP-CAP-37 → connector-enablement runway. This is the
authority-of-record for stage order and safety posture. Local repo + tests + stage docs
are the source of truth.

## OP-CAP-37 — Non-executed ChangeRequest Candidate

- OP-CAP-37 is the **non-executed external-sync ChangeRequest candidate** layer.
- On a clean `assemble-draft` (status `DRAFT_ASSEMBLED`, no approval pending),
  `QuoteReviewService.assembleDraft` prepares **exactly one** tenant-scoped candidate via
  `ChangeRequestService.prepareQuoteExternalSyncCandidate`.
- Candidate shape: `targetSystem=INTERNAL_SYNC_CANDIDATE` (neutral target the demo executor
  refuses — `executeStage9DemoChangeRequest` requires `DEMO_ERP`),
  `requestedAction=QUOTE_EXTERNAL_SYNC_CANDIDATE`, `sourceType=QUOTE_REVIEW`,
  `sourceId=quoteId`, `approvalStatus=PENDING_APPROVAL`, `executionStatus=EXECUTION_DISABLED`.
- Dedup by deterministic per-quote idempotency key
  (`opcap37:quote-external-sync-candidate:<tenant>:<quote>`): repeated assemble never creates a
  duplicate active candidate. Audit: `QUOTE_DRAFT_EXTERNAL_SYNC_CANDIDATE_CREATED` / `..._REUSED`.
- **Real external writes remain disabled.** No connector call, no executable outbox event,
  `externalExecution=DISABLED`. When approval is still required, no candidate is prepared.

## Current Accepted Order

1. **OP-CAP-37** — Non-executed ChangeRequest candidate (this slice).
2. **OP-CAP-38/COORD** — Local AI Review + Metrics Gate.
3. **AI Model Runtime Foundation**.
4. **Local Ollama Review Harness hardening**.
5. **Connector Capability Policy / Sandbox / Dry-run**.
6. **ChangeRequest Approval / Execution Guard**.
7. **Real ERP / 1C / external connector enablement** — only after policy, approval, dry-run
   and tests.

Do not reorder. Do not skip a gate. Do not enable real external connector execution before
gate 7.

## Authority Model

- AI / local models are **reviewers, not authority**. They suggest; they do not approve,
  validate, or write business truth.
- Local repo + tests + stage docs are the **source of truth**.
- ChatGPT / Codex / Claude / Ollama outputs are **advisory** unless verified by tests and code.
- Engineering law: AI suggests. Rules validate. Human approves if risky. Backend writes.
  Audit records. Outbox triggers external side effects only after explicit policy/approval/
  execution gates.

## Safe Core Path

Quote Review → Draft Assembly → Non-executed ChangeRequest candidate → Audit →
`externalExecution=DISABLED`.
