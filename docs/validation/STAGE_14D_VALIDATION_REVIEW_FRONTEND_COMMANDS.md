# OP-CAP-14D — Frontend Wiring for Operator Validation Review Commands

Wires the OP-CAP-14C operator command endpoints into the OP-CAP-14B read-only validation review
workspace. The detail page stays server-rendered and read-only; a single narrow client component adds
controlled action forms. No new backend logic, no external write, no master-data mutation.

## Route

`/validations/{validationRunId}/review` — unchanged
(`app/(dashboard)/validations/[id]/review/page.tsx`). The server page still fetches the bounded
OP-CAP-14A read model and renders `ValidationReviewDetailView` (read-only), now followed by
`ValidationReviewActionsClient` (actions).

## UI actions wired

In `components/validation-review-actions.tsx` (`"use client"`):

- **Correct an extracted value** (`CorrectionForm`) — choose FIELD or LINE_ITEM, pick the target from
  the loaded review, enter a bounded corrected value (FIELD) or corrected quantity/UOM (LINE_ITEM), and
  a required reason. No raw/JSON editing surface (plain bounded inputs only).
- **Resolve a validation issue** (`IssueResolutionControls`) — pick an issue, enter a required reason,
  and click **Resolve**, **Ignore**, or **Escalate**.
- **Request approval** (`ApprovalRequestControl`) — optional line item + required reason creates a
  pending approval request. Approve or reject decisions are intentionally **not** performed here.

## Command endpoints used

Via `lib/validation-review-command-api.ts` (POST only):

- `POST /api/v1/validations/{validationRunId}/review/corrections` → `submitValidationReviewCorrection`
- `POST /api/v1/validations/{validationRunId}/review/issues/{issueId}/resolution` → `resolveValidationReviewIssue`
- `POST /api/v1/validations/{validationRunId}/review/approval-requests` → `requestValidationReviewApproval`

No other mutation endpoint is called. Tenant id comes from `NEXT_PUBLIC_DEMO_TENANT_ID` and is sent as
`X-Tenant-Id`; it is never taken from user input or a request body.

## Permission behavior

The 14C endpoints require `REVIEW_ACTION` (enforced server-side by `ApiPermissionInterceptor`). The
client maps backend responses to bounded, user-safe messages:

- `403` → "You do not have permission for this validation review action (REVIEW_ACTION required)."
- `404` → "This review item is no longer available."
- `400`/other → the bounded backend `message` only (never a raw response dump or stack trace).

## State refresh

On backend success the action clears its inputs, shows the backend `message`, and calls
`router.refresh()` so the server page re-fetches the 14A read model. No completed/approved state is
shown before the backend confirms it.

## UX safety

- Buttons are disabled while a command is in flight (`disabled={submitting}`).
- Required reason is enforced client-side before the call (backend remains authoritative).
- Inputs are size-bounded (reason ≤ 512, value ≤ 512, UOM ≤ 16) and the line-item form exposes only the
  safe quantity/UOM fields supported by the contract.
- No raw AI payload, document body, prompt, secret, token, stack trace, or unbounded JSON panel is
  rendered or editable.
- Read-only fields/evidence/audit panels from 14B remain present and unchanged.

## No external writes / final order / master-data mutation

This stage adds no final quote/order creation, no ERP/1C/accounting/warehouse/connector write, and no
product/customer/inventory/price master-data mutation. All mutations route exclusively through the
three 14C Core API endpoints; the backend business-write boundary is unchanged.

## Known limitations

- Refresh is a full `router.refresh()` (server re-fetch); there is no optimistic local patch of the
  read model.
- Field correction does not auto re-run the deterministic validation engine (re-validation remains a
  separate `VALIDATION_RUN` action), matching the 14C backend behavior.
- The approval control creates only a pending approval request; the approve/reject decision flow stays
  in the existing approval/review-case infrastructure and is not surfaced here.

## Verification

From `apps/web-dashboard`:

- `node --test tests/validation-review-commands.test.mjs tests/validation-review-detail.test.mjs` → 24 pass
- `node --test tests/*.test.mjs` → 169 pass
- `npm run lint` → clean
- `npm run typecheck` → clean
- `npm run build` → compiles; `/validations/[id]/review` route builds

## Next intended stage

Operator approval decision surface and/or richer per-row inline actions, if scoped in a later stage.
