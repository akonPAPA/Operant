# OP-CAP-14B — Operator Validation Review Workspace UI

Read-only Next.js operator workspace that consumes the OP-CAP-14A backend review contract. It lets an
operator see what was extracted, what deterministic validation decided, what issues exist, what
evidence supports the result, and what safe next steps are available. It introduces **no** business
write path.

## Route

`/validations/{validationRunId}/review`

Implemented as `app/(dashboard)/validations/[id]/review/page.tsx`, nested under the existing
`/validations/[id]` dynamic slug to avoid a parallel navigation structure and a Next.js slug-name
conflict (`[id]` is reused; `id` is the validation run id). The existing validation detail page
(`/validations/[id]`) now links to it via an **Open operator review** button. No new top-level sidebar
entry was added — the existing **Validations** nav entry remains the parent surface.

## API endpoint consumed

- `GET /api/v1/validations/{validationRunId}/review` (primary, used by the page)
- `GET /api/v1/validations/extractions/{extractionResultId}/review` (also exposed by the helper for
  future extraction-anchored entry points)

Client: `lib/validation-review-detail-api.ts` — typed, tenant-scoped, **read-only** (`GET` only).
Tenant id comes from `NEXT_PUBLIC_DEMO_TENANT_ID` and is sent as `X-Tenant-Id`; it is never taken from
user input or a request body. `403` → `VALIDATION_READ` permission message; `404` → bounded
"not found for this tenant" message. A distinct filename is used because the existing
`lib/validation-review-api.ts` serves the unrelated OP-CAP-09A `ExceptionCase` surface
(`/api/v1/validation-review`).

## Sections rendered

Components live in `components/validation-review-detail.tsx`:

1. `ValidationReviewHeader` — validation status, routing decision, advisory-only marker, run id,
   extraction result id, source/channel, intent, document type, worker status, issue counts, approval
   requirement count, created/completed timestamps.
2. `ExtractedFieldsPanel` — field name, extracted/normalized value, confidence, validation status,
   bounded evidence reference, linked-issue marker.
3. `ExtractedLineItemsTable` — line index, raw SKU, matched product id + match status, description,
   quantity, UOM, confidence, validation status, evidence reference, issue marker.
4. `ValidationIssuesPanel` — severity, code, target type + reference/index, blocking flag,
   operator-visible explanation, status.
5. `SourceEvidencePanel` — bounded snippets only, with evidence type and page/offset references.
6. `AuditTimelinePanel` — bounded who/what/when/action rows, with a graceful empty state.
7. `AllowedActionsPanel` — declarative next-action hints rendered with an enabled / "not yet
   implemented" state and the permission a future action would require.

## Safety boundaries

- Read-only: the API client issues `GET` only (no `POST`/`PATCH`/`PUT`/`DELETE`); the page is a server
  component that fetches and renders.
- No correction, approval, draft/quote/order creation, ERP/1C, connector or bot action is wired. The
  allowed-actions panel renders hints only — no button is connected to a mutation endpoint, and there
  are no fake local-only approval/correction flows.
- No raw AI advisory payload, prompt text, full document/message body, secret, token or stack trace is
  rendered. Only the bounded fields from the OP-CAP-14A contract are displayed; evidence is shown as
  short snippets, audit as bounded metadata (no raw metadata JSON dump).

## No-mutation guarantee

The workspace performs no server-side mutation. It does not call handoff, validation-run trigger,
draft preparation, approval or connector endpoints. The backend (OP-CAP-14A) remains the sole source
of truth and is itself read-only.

## Known limitations

- The page is anchored on `validationRunId`. The extraction-anchored helper exists but no dedicated
  extraction-entry route was added in this slice.
- There is no validation-run list/queue feeding this page yet; entry is via the existing validation
  detail page link or a direct run-id URL. A queue/list entry point is deferred.
- Frontend tests are source-inspection style (no live backend), matching the existing repo convention.

## Next intended stage

OP-CAP-14C — operator correction / approval command layer (the first stage that would wire actual
mutation commands behind the permissioned backend; out of scope here).
