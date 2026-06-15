# Idempotency and Request Integrity

Date: 2026-06-15

OP-CAP-17C adds backend-owned idempotency and request-hash conflict detection for the quote mutation surfaces touched by the current slice.

## Protected Quote Mutations

Protected endpoints:

- `POST /api/v1/quotes/from-rfq`
- `POST /api/v1/quotes/{id}/approve`
- `POST /api/v1/quotes/{id}/reject`
- `POST /api/v1/quotes/{id}/request-changes`
- `POST /api/v1/quotes/{id}/convert-to-internal-order`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/resolve`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/reject`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/apply-fix`
- `POST /api/v1/quote-review/{quoteId}/issues/{issueId}/escalate`
- `POST /api/v1/quote-review/{quoteId}/customer`
- `POST /api/v1/quote-review/{quoteId}/lines/{lineId}/correct`
- `POST /api/v1/quote-review/{quoteId}/lines/{lineId}/substitutes/select`
- `POST /api/v1/quote-review/{quoteId}/lines/{lineId}/substitutes/reject`

## Server Rules

- `Idempotency-Key` is accepted as HTTP metadata and is preferred over body fields.
- The key is stored only as a SHA-256 hash in `idempotency_key.key_hash`.
- The request hash is SHA-256 over command type, target resource, normalized business payload, tenant context, and trusted actor context when available.
- The request hash excludes metadata fields such as idempotency key, tenant id, actor id, actor role, CSRF token, correlation id, request id, and timestamp.
- Same tenant + same actor context + same key + same request hash returns the stored safe response without repeating business, audit, or outbox side effects.
- Same tenant + same key + different request hash returns `409 IDEMPOTENCY_KEY_CONFLICT`.
- Same tenant + same key + different actor context returns `409 IDEMPOTENCY_KEY_CONFLICT` and does not expose the stored response.
- Same key in another tenant is independent because storage remains tenant-scoped.
- In-progress duplicates return `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`.
- Business validation failures are not converted into fake success.

## Frontend Rules

- Quote workspace and quote review cockpit generate opaque attempt keys with `crypto.randomUUID()`.
- The fallback uses `crypto.getRandomValues()` to create a UUID-shaped opaque value.
- If neither API exists, the UI fails closed with an internal error message.
- Keys are stored in `useRef` per action/resource attempt and are cleared only after confirmed success.
- Retryable failures retain the same key for the same action/resource attempt.
- Ref-based in-flight locks block same-tick duplicate clicks; React disabled state remains UX only.
- Idempotency keys are sent as `Idempotency-Key` headers and stripped out of JSON business bodies.

## Boundaries

- Frontend still calls Core API only.
- Frontend does not import DB, repository, connector, or server adapter modules in the touched quote surfaces.
- Connector writes remain outside this slice and must continue through ChangeRequest/approval-controlled paths.
- AI/bot/frontend surfaces do not directly write trusted quote state.
- No HMAC/shared secret is added to browser requests; browser integrity depends on TLS, tenant/session context, authorization, CSRF where configured, and backend idempotency.

## Remaining Limitations

- This slice does not implement a new production auth system, refresh-token rotation, or global CSRF retrofit.
- Cookie-based CSRF behavior was not proven because the current local quote clients use tenant/demo headers rather than a fully configured cookie session.
- Connector/webhook replay behavior was inspected only as an existing separate boundary; this slice does not change connector/webhook signature verification.
- Only the touched quote mutation surfaces are protected by the new generic idempotency wrapper.
