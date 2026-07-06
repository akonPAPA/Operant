# Commerce Intelligence — Browser / Manual Evidence (PR #251)

Browser and manual evidence for the tenant-scoped, read-only Commerce Intelligence view added in
PR #245.

```
/demo -> Telegram RFQ handoff -> /channels/rfq-handoffs
     -> AI Work advisory suggestion -> operator review
     -> review-required draft quote -> COMPLETE_DEMO / DECLINE_DEMO
     -> SAFE_DEMO_TERMINAL
     -> /commerce-intelligence explains the flow read-only (no mutation)
```

## Non-negotiable law preserved

Commerce Intelligence is a **read-only projection**. It creates no business state, invokes no runtime
guard, calls no connector, and requests no external write. `externalExecution=DISABLED`,
`connectorCall=NOT_INVOKED`, `outbox=NOT_REQUESTED`. It exposes no tenant / actor / source / audit /
idempotency / provider / runtime internals.

- Endpoint: `GET /api/v1/commerce-intelligence/demo-flow` (`ANALYTICS_READ`)
- Route: `/commerce-intelligence`
- Contract: `CommerceIntelligenceDtos` (public, tenant-operator-safe DTOs)

## 1. What was actually executed in this environment

Deterministic render-state proof and targeted contract/security tests were run. A full
PostgreSQL + live-browser screenshot walkthrough was **not** executed here (no local PostgreSQL /
running app in this environment) — the documented walkthrough in section 4 remains the manual
procedure, and live-DB browser proof stays deferred (see section 6 and the fix-notebook item).

Commands run:

```bash
# Frontend — real component render proof (react-dom/server), no live backend needed.
# The render test resolves the app root from import.meta.url, so it runs from EITHER directory:
node --test apps/web-dashboard/tests/commerce-intelligence.render.test.mjs        # 6/6 pass (repo root)
( cd apps/web-dashboard && node --test tests/commerce-intelligence.render.test.mjs )  # 6/6 (app dir)

# The source-contract and data-boundary tests read files relative to process.cwd(), so they must be
# run from the app directory (they are NOT root-cwd resolvable). Run the combined suite there:
cd apps/web-dashboard
node --test tests/commerce-intelligence.render.test.mjs \
            tests/commerce-intelligence.test.mjs \
            tests/ui-data-boundary.test.mjs                                       # 17/17 pass
cd ../..
npm --prefix apps/web-dashboard run lint                                          # clean
npm --prefix apps/web-dashboard run typecheck                                     # clean
npm --prefix apps/web-dashboard run build                                         # /commerce-intelligence route built

# Backend — read-model + route classification + response-leak + role matrix
mvn -f apps/core-api/pom.xml -Dtest="CommerceIntelligenceControllerTest,\
CommerceIntelligenceDemoFlowServiceTest,ApiRouteSecurityClassificationTest,\
ResponseDtoLeakContractTest,ApiPermissionRoleMatrixTest" test                     # 65/65 pass
```

`commerce-intelligence.render.test.mjs` compiles the real
`components/commerce-intelligence-demo-flow.tsx`, stubs only `next/link`, then renders each
operator-facing state with `react-dom/server` and asserts on the produced HTML — including that
decoy internal/raw fields (`tenantId`, `actorId`, `idempotencyKey`, `rawPayload`, `prompt`, `secret`,
`channelConnectionId`) never reach the DOM. It resolves the frontend app root from
`import.meta.url` (not `process.cwd()`), so it is runnable from both the repo root and
`apps/web-dashboard`. The existing `commerce-intelligence.test.mjs` and `ui-data-boundary.test.mjs`
are `process.cwd()`-relative source-inspection tests and must be launched from `apps/web-dashboard`;
a single root-level `node --test` that mixes all three will fail on those two (documented here, not
faked).

## 2. Observed render states (proven by the render test)

| State | Observation |
|---|---|
| Populated | Six summary cards (RFQs captured, Pending review, In review, AI advisory suggestions, Review-required draft quotes, Safe demo terminal decisions); Safety state table with `DISABLED` / `NOT_INVOKED` / `NOT_REQUESTED`; Runtime-control posture (rate backpressure gated); Blocking bottlenecks; Recent demo flows with safe request preview + humanized `safe demo terminal`; explicit "Not proven by this read model" section. |
| Empty | "No open blocking issues were observed…" and "No RFQ handoff flows are available for this tenant."; honest zero summary cards (no faked metrics). |
| Unavailable (null data) | "Commerce Intelligence unavailable" + safe default copy + links to `/demo` and `/channels/rfq-handoffs`; no raw error. |
| Backend error | Mapped bounded message only (e.g. "…temporarily unavailable. Please try again shortly."); no `{` raw JSON, no stack trace, no backend exception string. |
| Partial soft error | Populated content plus a soft error banner render together, still leak-free. |

Data boundary asserted at render time: no decoy sentinel and no raw response key (`tenantId`,
`idempotencyKey`) appears in the HTML; no `<pre>` object dump; no `JSON.stringify` of the response.

## 3. Local environment (for the live walkthrough)

Backend PowerShell session:

```powershell
$env:SPRING_PROFILES_ACTIVE = "demo"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:15432/operant_commerce_intel_proof"
$env:SPRING_DATASOURCE_USERNAME = "<local-demo-db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<local-demo-db-password>"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED = "true"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED = "false"
$env:ORDERPILOT_CORS_ALLOWED_ORIGINS = "http://localhost:3000"
$env:ORDERPILOT_DEMO_RFQ_HANDOFF_ENABLED = "true"
```

Frontend PowerShell session:

```powershell
$env:CORE_API_BASE_URL = "http://localhost:8080"
$env:ORDERPILOT_DEMO_MODE = "true"
$env:ORDERPILOT_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:NEXT_PUBLIC_CORE_API_URL = "http://localhost:8080"
$env:NEXT_PUBLIC_DEMO_MODE = "true"
$env:NEXT_PUBLIC_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
```

Gateway settings above are local/demo-only; production and signed modes must not use unsigned gateway
headers. The browser sends **no** tenant/actor/source/status/runtime field — the frontend attaches
`X-Tenant-Id` (from server config) and `X-OrderPilot-Permissions: ANALYTICS_READ`; Core resolves tenant
scope and all authority.

## 4. Browser walkthrough

1. Start PostgreSQL, backend (`mvn -f apps\core-api\pom.xml spring-boot:run`), frontend
   (`npm --prefix apps\web-dashboard run dev`); confirm `GET /api/v1/health` returns `200`.
2. Seed / trigger the RFQ demo flow (see `docs/runbooks/post-pr239-real-demo-proof.md` §7):
   `.\scripts\seed-local-demo.ps1`, or open `http://localhost:3000/demo` and click
   **Send demo Telegram RFQ**.
3. Open the RFQ handoff workspace `http://localhost:3000/channels/rfq-handoffs`; generate the AI
   suggestion, start review, create the draft quote, and (optionally) complete/decline the demo to
   reach a `SAFE_DEMO_TERMINAL`.
4. Open `http://localhost:3000/commerce-intelligence`.
5. Verify the six **summary cards** show tenant-observed counts.
6. Verify the **Recent demo flows** list shows the seeded handoff (safe request preview, humanized
   intent/status, AI schema/risk, draft/validation/terminal columns).
7. Verify the **Safety state** table: External writes `DISABLED`, Connector call `NOT_INVOKED`,
   Change request `NOT_REQUESTED`, Outbox `NOT_REQUESTED`; observed row counts show the measurement
   scope, not fake zeros.
8. Verify the **Runtime-control posture** table shows the stable PR #244 posture labels
   (rate/backpressure gated; AI advisory guarded; denial telemetry not measured).
9. Verify **Blocking bottlenecks** shows any open blocking validation issues (or the safe empty
   message).
10. Verify the **safe empty state**: repeat against a tenant with no handoffs and confirm the safe
    empty messages and honest zeros (no faked metrics).
11. Verify the **safe backend error state**: stop the backend and reload `/commerce-intelligence`;
    confirm a bounded message ("Core API is not reachable." / "…temporarily unavailable…") and no raw
    stack trace, JSON body, or backend exception string.

## 5. Screenshots

Screenshots are optional and were **not** captured in this environment. If captured during a live
walkthrough, store them here and reference by file name (do not commit any image containing secrets,
tokens, connection strings, real customer data, or internal identifiers):

- `commerce-intelligence-populated.png` — summary + safety + runtime + bottlenecks + recent flows.
- `commerce-intelligence-empty.png` — safe empty state.
- `commerce-intelligence-backend-error.png` — bounded error state.

Placeholders only; no screenshot is required to accept this evidence because the render states are
deterministically proven by `commerce-intelligence.render.test.mjs`.

## 6. Not proven

- Live PostgreSQL + real-browser screenshot walkthrough of `/commerce-intelligence` (documented in
  section 4; not executed in this environment — no local PostgreSQL / running app here).
- Real analytics warehouse / OLAP, KPI model.
- Production bounded time-range filtering and export.
- Real revenue / conversion / revenue-recognition metrics (demo completion is not a real order, sale,
  revenue event, invoice, ERP sync, or customer commitment).
- Cross-channel non-demo intelligence.
- A separate staff/support intelligence plane.
- Runtime denial telemetry dashboard and distributed runtime-guard telemetry.

## 7. No secrets

No secrets, tokens, credentials, connection strings, or real customer data appear in this document,
in the rendered page, in the read-model response, or in any referenced screenshot. Database
credentials in section 3 are placeholders. The read model exposes no provider payload, prompt text,
secret reference, connector credential, idempotency key, outbox id, change-request id, or audit
internal.
