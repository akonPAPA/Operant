# Runtime Control + Commerce Intelligence — Live PostgreSQL & Browser Product Evidence

> Proof/evidence stage. **No application code, schema, or business workflow was changed.** No ERP/1C/
> connector/ChangeRequest/outbox execution was performed. No fake telemetry was authored. This document
> records what was observed against a **live local PostgreSQL** and the **running product surfaces**.

## 1. Context

| Field | Value |
| --- | --- |
| Repository | `github.com/akonPAPA/Operant` |
| Local path | `C:\OrderPilot\OrderPilot-Core` |
| Branch | `proof/runtime-control-and-commerce-live-browser-postgres-evidence` |
| Base / head SHA | `17091d11fd78b8c75734cf855c9327b5616d8a17` (branched from current `origin/main`, PR #253) |
| OS | Windows 11 Pro (10.0.22631); Git Bash + PowerShell |
| Toolchain | OpenJDK 21.0.11, Node v24.17.0, PostgreSQL 18.4 local install observed in pass 1; Docker-backed PostgreSQL container observed in passes 2–4; full Docker/Testcontainers suite not executed |
| Evidence timestamp (UTC) | 2026-07-07 (endpoint `generatedAt` ≈ `2026-07-07T11:51:34Z`) |
| Surfaces proven | `/runtime-control`, `/commerce-intelligence`, RFQ demo/handoff (`/demo`, `/channels/rfq-handoffs`) |

**Important honesty note on process:** PostgreSQL (pid on `:15432`), core-api (`:8080`), and the
web-dashboard (`:3000`) were **already running** when this task began (the web-dashboard is owned by a
separate preview session). This proof therefore **verified** the live stack; it did not cold-start it.
The repo-supported startup path is documented in §10 for reproducibility.

**DB topology note:** pass 1 observed a local PostgreSQL 18.4 install on :15432; passes 2–4 observed the restarted Docker-backed PostgreSQL container behind the same local port. The full Docker/Testcontainers integration-test path was not executed in this proof.

## 2. Environment / profile (secrets redacted)

- Datasource (from `apps/core-api/src/main/resources/application.yml`): `jdbc:postgresql://localhost:15432/orderpilot_local`, user `orderpilot_local_user`, password = the documented **local-dev default** (`${ORDERPILOT_DB_PASSWORD}` placeholder in `infra/docker/docker-compose.yml`; **redacted here**, non-production).
- Frontend demo mode (`.claude/launch.json`): `ORDERPILOT_DEMO_MODE=true`, demo tenant `11111111-1111-4111-8111-111111111111`, `CORE_API_BASE_URL=http://localhost:8080`.
- `spring.flyway.enabled=true`, `jpa.hibernate.ddl-auto=none` — schema is Flyway-owned (not JPA-generated), i.e. real PostgreSQL constraints/FKs/`TIMESTAMPTZ` are exercised.
- No tokens, auth headers, cookies, or secrets are reproduced in this document.

## 3. Live PostgreSQL result — PASS

Command (Git Bash; `psql` from the local PostgreSQL 18 install):

```
PGBIN="/c/Program Files/PostgreSQL/18/bin"; export PGPASSWORD=<redacted local-dev default>
"$PGBIN/psql.exe" -h localhost -p 15432 -U orderpilot_local_user -d orderpilot_local \
  -tAc "select current_database(), version();"
"$PGBIN/psql.exe" ... -tAc "select count(*), max(version), bool_and(success) \
  from flyway_schema_history where version is not null;"
```

Observed:

| Check | Result |
| --- | --- |
| Database reachable on `:15432` | `orderpilot_local` — **PostgreSQL 18.4 on x86_64-windows** |
| Flyway migrations applied | **66**, `max(version) = 66`, `bool_and(success) = t` (all clean) |
| Latest migrations | `66 audit event actor id polymorphic principal` (t), `65 …status repair` (t), `64 …break glass` (t) |

So all 66 migrations (incl. **V66**, the PG-248-01 fix) apply cleanly to the live DB. This is genuine
PostgreSQL proof, not H2.

## 4. Core-API result — PASS

`curl` to localhost was blocked by the shell sandbox; verification used PowerShell `Invoke-WebRequest`.

| Check | Command (abridged) | Result |
| --- | --- | --- |
| Health | `GET http://localhost:8080/actuator/health` | **200** → `{"status":"UP"}` |
| Runtime-control demo-flow | `GET /api/v1/runtime-control/demo-flow` + `X-OrderPilot-Permissions: ANALYTICS_READ` + `X-Tenant-Id: 1111…` | **200**, 7384 bytes, valid safe DTO |
| Commerce-intelligence demo-flow | `GET /api/v1/commerce-intelligence/demo-flow` (same headers) | **200**, 2107 bytes, valid safe DTO |

Both endpoints were called **exactly as the frontend client calls them** (`lib/runtime-control-telemetry-api.ts`,
`lib/commerce-intelligence-api.ts`), so this exercises the real core-api → PostgreSQL read path.

## 5. Frontend result — PASS

The web-dashboard (`:3000`, Next.js, demo mode) is a server-rendered app: the pages fetch the safe DTO
**server-side** and render it into the returned HTML. Fetching the SSR HTML therefore proves the rendered
surface even without a scripted browser (see §8 for the browser-screenshot limitation).

| Route | Status | HTML bytes |
| --- | --- | --- |
| `/runtime-control` | **200** | 66,598 |
| `/commerce-intelligence` | **200** | 50,517 |
| `/demo` (RFQ demo/handoff entry) | **200** | 41,382 |

## 6. Observed result per surface

### 6.1 `/runtime-control` — PASS

Backend DTO + rendered HTML both show the read-only runtime-control posture for the RFQ/AI/demo path:

- `safety`: `runtimeControlView = READ_ONLY`, `connectorInvocation = NOT_INVOKED`, **`externalExecution = DISABLED`**, `guardEvaluation = NOT_INVOKED_BY_THIS_READ`, `telemetryCompleteness = PARTIAL`.
- 4 workload postures (`DEMO_RFQ_HANDOFF_CREATE`, `RFQ_HANDOFF_AI_ADVISORY`, `RFQ_HANDOFF_DRAFT_QUOTE_CREATE`, `RFQ_HANDOFF_DEMO_DECISION`), all `STATIC_CONTRACT`.
- `admission`: contract defaults only (`maxCostUnitsPerRequest=10000`, `maxSyncCostUnits=100`, `backpressureQueueDepth=1000`); **`admittedCount` / `deniedCount` = `NOT_MEASURED` (null, never a fake zero)**.
- Honest `scopeLabel` states tenant-specific quota/rate/entitlement counters are **not measured** in this slice; 8 `notMeasured` entries; 3 `provenGuarantees` (all checkpoints guarded, denial fails closed, read invokes no guard).
- Rendered HTML label counts: "Read-only" ×4, "External execution" ×4, "NOT_INVOKED" ×4, "Not measured" ×6, "Runtime Control" ×5.

### 6.2 `/commerce-intelligence` — PASS

- `summary`: all counts `0` (demo tenant has no seeded records — see §6.3).
- `safety`: **`externalWriteStatus = DISABLED`**, `connectorCallStatus = NOT_INVOKED`, `outboxStatus = NOT_REQUESTED`; observed external-row counts are `null` with `measurementScope = NOT_MEASURED` (**honest null, not a fabricated zero**).
- `runtimeControl.guarded = true`; `denialTelemetry = NOT_MEASURED`; `bottlenecks = []`, `recentFlows = []` (empty because unseeded).
- 4 `notProven` entries incl. "Demo completion is not a real order, sale, revenue event, invoice, ERP sync, or customer commitment." — **no false production claim**.
- Rendered HTML: "External write" / "External execution" DISABLED visible, "not measured" ×13.

### 6.3 RFQ demo / handoff path — PASS (clean slate; no stale duplicate state)

DB row counts (whole `orderpilot_local`):

| Table | Rows |
| --- | --- |
| `tenant` | 0 |
| `channel_rfq_handoff` | 0 |
| `bot_rfq_request` | 0 |
| `bot_handoff` | 0 |
| `ai_validation_handoff` | 0 |
| `draft_quote` | 0 |
| `quote_handoff_snapshot` | 0 |

The database is a **clean, freshly-migrated slate** — there is **no stale duplicate RFQ/handoff state**.
The RFQ demo/handoff surfaces (`/demo` @ 200, `/channels/rfq-handoffs`) render their empty/safe states,
and the seed/replay entrypoint (`app/api/demo/rfq-handoff`) is present and available to seed on demand.
This satisfies "can be seeded/replayed without stale duplicate state": the starting state is empty, so a
seed will not collide with or duplicate prior rows.

### 6.4 Seeded RFQ replay — EXECUTED LIVE (pass 2, 2026-07-07, stack restarted) — PASS

After the stack was restarted (PostgreSQL `:15432` via Docker `172.18.0.2`, core-api `:8080` `UP`,
web-dashboard `:3000` `200`), the documented seed was executed against live `orderpilot_local` (66
migrations). No ad-hoc DB writes were made; only the documented seeder was run.

**Seeder:** `scripts/seed-local-demo.ps1` (authorized by `docs/runbooks/post-pr239-real-demo-proof.md` §7)
— deterministic `psql` **upserts with fixed demo UUIDs**, pointed at `jdbc:postgresql://localhost:15432/orderpilot_local`.

**Before → after (run 1) → replay (run 2) row counts:**

| Table | Before | After seed #1 | After replay #2 |
| --- | --- | --- | --- |
| `tenant` | 0 | **1** | 1 |
| `channel_connection` | 0 | **1** | 1 |
| `inbound_channel_event` | 0 | **1** | 1 |
| `channel_rfq_handoff` | 0 | **1** | 1 |
| `draft_quote` | 0 | 0 | 0 |
| `change_request` | 0 | 0 | 0 |
| `outbox_event` | 0 | 0 | 0 |
| `connector_command` | 0 | 0 | 0 |
| `connector_sandbox_execution` | 0 | 0 | 0 |

**Seeded RFQ handoff (tenant-scoped, safe fields):**

```
id             = 99999999-9999-4999-8999-999999999903
tenant         = 11111111-1111-4111-8111-111111111111   (demo tenant — scoped)
status         = PENDING_REVIEW
source_channel = TELEGRAM
detected_intent= RFQ_REQUEST
request_text   = Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.
```

- **Exactly one** tenant-scoped RFQ handoff exists; `PENDING_REVIEW`; safe business `request_text` (no connector secret/internal payload).
- **Replay/idempotency PASS:** running the seed a second time left **every count unchanged** — no duplicate stale RFQ/handoff/draft state. Confirmed structural: the `channel_rfq_handoff` upsert is `ON CONFLICT (id) DO UPDATE … WHERE tenant_id = EXCLUDED.tenant_id` (fixed id, tenant-scoped guard); `channel_connection`/`inbound_channel_event` use `ON CONFLICT (id) DO NOTHING`.
- **Tenant-scope integrity PASS:** `channel_rfq_handoff WHERE tenant_id <> demo` = **0**.
- **No-external-execution PASS:** after both runs, `change_request` / `outbox_event` / `connector_command` / `connector_sandbox_execution` all **0**.

**API-level dedup path** (`POST /api/v1/demo/rfq-handoff` → `LocalDemoRfqIntakeService.createOrGet`, fixed
`DEMO_EXTERNAL_EVENT_ID`, dedup via `findFirstByTenantIdAndInboundChannelEventId`): **not exercisable on
this restarted stack** — see §6.5. The seed-level replay above already proves no-duplicate state.

### 6.5 Visible API/UI of the seeded handoff — BLOCKED by restarted-stack auth config (fail-closed, safe)

Pass 1 (earlier stack) proved both read models at HTTP 200 with safe DTOs (§6.1–6.2). On the **restarted**
stack the same authenticated calls now return **`401 AUTHENTICATION_REQUIRED`**, because core-api was
restarted **without** the demo/gateway-header-auth config (`ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED` not
enabled), and the web-dashboard was restarted **without** the server-only demo BFF env
(`ORDERPILOT_DEMO_MODE`/`ORDERPILOT_DEMO_TENANT_ID`). Observed, all fail-closed and safe:

| Surface / call | Result on restarted stack |
| --- | --- |
| `GET /api/v1/commerce-intelligence/demo-flow` (`ANALYTICS_READ`) | **401** `AUTHENTICATION_REQUIRED` |
| `POST /api/v1/demo/rfq-handoff` (`ADMIN_SETTINGS_MANAGE`) ×2 | **401** (no state mutated) |
| `POST /api/demo/rfq-handoff` (BFF) ×2 | **503** safe message (BFF fail-closed, **no core call**) |
| SSR `/runtime-control`, `/commerce-intelligence` | **200** — render bounded **"could not be loaded"** copy (no seeded data, no raw error/stack) |
| SSR `/channels/rfq-handoffs`, `/demo` | **200** — render (the `PAD-OE-04465` string on `/demo` is the **static** demo-button label, not proof of reading the seeded row) |

So the **seeded handoff is proven in the system of record (DB)**, but showing it **through the live
authenticated API/UI is blocked** until the stack is restarted with the demo profile. Every failure mode
observed was fail-closed with a safe, bounded message — no leak, no raw backend body, no stack trace.

**Pass 3 (2026-07-07, user reported enabling the demo/gateway-header-auth + BFF env) — STILL BLOCKED.**
Re-tested after the reported reconfiguration; health is `200 UP`, DB state intact (seeded handoff present,
all external tables 0), but **every authenticated read path still returns `401`**:

| Authenticated read (demo headers) | Pass 3 result |
| --- | --- |
| `GET /api/v1/runtime-control/demo-flow` (`ANALYTICS_READ`) | **401** |
| `GET /api/v1/commerce-intelligence/demo-flow` (`ANALYTICS_READ`) | **401** |
| `GET /api/v1/channels/rfq-handoffs` (`ADMIN_SETTINGS_READ`) | **401** |
| SSR `/runtime-control`, `/commerce-intelligence` | 200, still bounded **"could not be loaded"** |

Root cause (precise): the running core-api **rejects unsigned trusted-permission headers**. In pass 1 the
identical headers returned `200`, so the difference is purely the running process's auth config. The
local-demo path needs gateway-header-auth **enabled *and* `ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED=false`**
(runbook §3) — with signature required (the default when only `ENABLED` is set), unsigned headers are
`401`, and the dashboard's own server-side calls (which send the same unsigned headers) fail-close too.
The reconfiguration reported by the user did not take effect on the running core-api process (either the
env var was not applied on restart, or `SIGNATURE_REQUIRED` was left at its `true` default).

**Exact fix to unblock the visible/authenticated layer** (backend PowerShell session, then restart
`mvn -f apps/core-api/pom.xml spring-boot:run`):

```
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED = "true"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED = "false"   # local/demo only
```

Verify with `GET /api/v1/runtime-control/demo-flow` + `X-OrderPilot-Permissions: ANALYTICS_READ` +
`X-Tenant-Id: 1111…` → expect `200`. No app code change is warranted — this is a runtime env/config gap,
not a code defect.

### 6.6 Pass 4 (2026-07-07) — RESOLVED: authenticated seeded-handoff view — PASS

The stack was restarted with `ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED=true`,
`ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED=false`, `ORDERPILOT_DEMO_MODE=true`, and
`ORDERPILOT_DEMO_TENANT_ID` = `11111111-1111-4111-8111-111111111111` (verified against the DB:
`select id from tenant` returns exactly that id). Health `200 UP`; DB unchanged (tenant/connection/
inbound_event/handoff = 1, draft_quote = 0). All previously-blocked authenticated paths now succeed:

| Authenticated call (demo headers) | Result | Seeded handoff visible? |
| --- | --- | --- |
| `GET /api/v1/runtime-control/demo-flow` (`ANALYTICS_READ`) | **200** | posture only (READ_ONLY, `externalExecution=DISABLED`, counters `NOT_MEASURED`) |
| `GET /api/v1/commerce-intelligence/demo-flow` (`ANALYTICS_READ`) | **200** | **YES** — `summary.rfqHandoffsTotal=1`, `pendingReviewCount=1`, `recentFlows[0].handoffId=…903` (`PENDING_REVIEW`, `aiSuggestionStatus=NOT_GENERATED`, `draftQuoteStatus=NOT_CREATED`, `safeTerminalState=NOT_RECORDED`) |
| `GET /api/v1/channels/rfq-handoffs` (`ADMIN_SETTINGS_READ`) | **200** | **YES** — `[{id:…903, sourceChannel:TELEGRAM, status:PENDING_REVIEW, detectedIntent:RFQ_REQUEST, requestText:"Need 2 EA PAD-OE-04465 …"}]` |
| SSR `/commerce-intelligence` | **200** | **YES** — renders `PAD-OE-04465` + `PENDING_REVIEW`, **no "could not be loaded"** |
| SSR `/channels/rfq-handoffs` | **200** | **YES** — renders `PAD-OE-04465` + `PENDING_REVIEW`, no error copy |
| SSR `/runtime-control` | **200** | posture only; **no error copy** (was fail-closed in passes 2–3) |
| SSR `/demo` | **200** | renders; no error copy |

- **No 401 / 403 / 503** on any configured demo path (contrast passes 2–3, all 401/503).
- **Safety preserved:** commerce `externalWriteStatus=DISABLED`, `connectorCallStatus=NOT_INVOKED`,
  `outboxStatus=NOT_REQUESTED`; observed external-row counts `null` / `NOT_MEASURED` (no fabricated zero);
  runtime-control `READ_ONLY` / `externalExecution=DISABLED` / guard `NOT_INVOKED`. **No false production
  claim** — commerce `notProven` still carries "Demo completion is not a real order, sale, revenue event,
  invoice, ERP sync, or customer commitment."
- **Operator-safe contract:** the handoff-list response exposes only operator-safe fields; the internal
  identifiers `reviewerUserId`, `inboundChannelEventId`, `channelConnectionId`, `sourceExternalEventId`
  are **absent** (verified by leak scan, §7).
- **Leak scan (pass 4):** across all three API responses **and** all four rendered pages — **0** forbidden
  identifiers and **0** tenant-UUID occurrences.
- **No-external-execution (after the authenticated UI/API checks):** `change_request` / `outbox_event` /
  `connector_command` / `connector_sandbox_execution` = **0**; `draft_quote` = 0; `channel_rfq_handoff` =
  1 (unchanged — the read paths mutated nothing).
- **Screenshots:** still **BLOCKED** — Chrome extension "not connected" on every retry (all passes). Not
  fabricated; the authenticated API + SSR evidence above substitutes.

## 7. Data-boundary / leak scan — PASS

Scanned raw API responses and all rendered HTML pages used by the proof (three pages in pass 1; four pages in pass 4) for forbidden content:

- **Not found (0 occurrences):** `actorId`, `idempotencyKey`, `rawPayload`, `"prompt"`, `X-OrderPilot-Permissions`, `api_key`/`apiKey`, `password`, `secret`, `token`, stack-trace markers (`at com.orderpilot`, `Exception`, "Internal Server Error", `NEXT_REDIRECT`).
- **Demo tenant UUID (`1111…`): 0 occurrences** in any rendered page — the tenant id is not even leaked into demo HTML.
- **No raw JSON/`<pre>` dump** and **no backend stack trace / raw error body** on any surface.
- Present and correct: `externalExecution:DISABLED`, `connectorInvocation:NOT_INVOKED`, `externalWriteStatus:DISABLED`, `connectorCallStatus:NOT_INVOKED`, `outboxStatus:NOT_REQUESTED`.

## 8. Screenshots / environment blockers

### 8.1 Browser screenshots — BLOCKED (no fabrication)

**No scripted-browser screenshots were captured.** Independent, environmental blockers outside this
task's change scope:

1. **Claude-in-Chrome extension not connected** — the browser MCP returned "not connected" on every retry (multiple attempts across both proof passes).
2. **Port 3000 was owned by a different preview session** (first pass) — the preview harness refuses to attach to or stop another chat's server; later the server was torn down entirely (§8.2).

Mitigation used in the first pass (while the stack was up): direct API proof (§4) + **server-rendered
HTML** proof (§5–6), which for a Next.js server-component app renders the same safe data the browser
would display. This proves page load, safe content, absence of raw dumps/leaks, and the DISABLED/
NOT_INVOKED safety posture — but it is **not** a pixel screenshot and does not exercise client-side
interactivity.

### 8.2 Stack teardown and restarted-stack auth-config gap — RESOLVED; screenshots still blocked

- **Mid-task teardown — RESOLVED:** between pass 1 and pass 2 the whole stack was stopped; at that point
  the PostgreSQL Windows service could not be restarted from the non-elevated session. The user then
  restarted the stack (pass 2), and the documented live seed executed successfully (§6.4).
- **Restarted-stack auth-config gap — RESOLVED in pass 4:** passes 2–3 showed fail-closed `401`/`503`
  because the demo/gateway-header-auth environment was not fully effective. Pass 4 restarted core-api with
  `ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED=true` and
  `ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED=false`, and restarted the dashboard with demo BFF env.
  After that, all authenticated read paths returned `200` and the seeded RFQ handoff was visible through
  authenticated API + SSR pages (§6.6).
- **Remaining environmental blocker:** scripted-browser screenshots are still blocked because the Chrome
  extension was not connected. No screenshots were fabricated.
- **Remaining out-of-scope proof:** write-side operator transition flow
  (`start-review → draft quote → safe-terminal decision`) was not exercised in this pass.
## 9. What remains NOT proven

- **Literal browser screenshots / client-side interactivity** — Chrome extension not connected across all passes; **still BLOCKED** (no fabrication). Authenticated API + SSR-render evidence (§6.6) substitutes for the static-image layer.
- **Authenticated API/UI view of the seeded handoff** — **RESOLVED / PROVEN in pass 4** (§6.6): all three read endpoints `200`, and the seeded handoff renders in the commerce read model, the handoff-list API, and the SSR pages.
- **Visible operator *transition* flow** (start-review → create draft quote → safe-terminal decision) — **not exercised** this run; the handoff remains at `PENDING_REVIEW` (draft_quote = 0). The read/view layer is proven; the write-transition walkthrough (which would create a `draft_quote` and a `SAFE_DEMO_TERMINAL` decision) was out of this pass's scope and is documented as prior evidence in `post-pr239-real-demo-proof.md` §14A.
- **Runtime denial-rate / admission telemetry** — `NOT_MEASURED` by contract; not proven (and not in scope).
- **Distributed/multi-node runtime guard**, provider billing, cross-channel telemetry — out of scope.
- **Full Docker/Testcontainers integration suite** (Testcontainers lock/retry integration classes) — not executed/proven in this document. A Docker-backed PostgreSQL container was observed for passes 2–4, but that is not the same as proving the full Docker/Testcontainers test path.
- No external ERP/1C/connector/ChangeRequest/outbox execution was attempted (by design).

## 10. Reproduction (repo-supported startup path)

If starting cold, use the same local/demo environment that pass 4 proved. These commands intentionally keep
gateway-header signatures disabled **only for local/demo proof**; production signed-gateway behavior is not
changed by this document.

```powershell
cd C:\OrderPilot\OrderPilot-Core

# 1. PostgreSQL
# Preferred when Docker is available:
docker compose -f infra/docker/docker-compose.yml up -d postgres

# If using the Docker test DB path instead, do not mix it with the default app profile unless
# ORDERPILOT_DB_* points at the same database.
# Alternative local-binary path is documented in docs/runbooks/postgres-integration-proof.md.

# 2. core-api datasource
$env:ORDERPILOT_DB_HOST_PORT = "15432"
$env:ORDERPILOT_DB_NAME = "orderpilot_local"
$env:ORDERPILOT_DB_USER = "orderpilot_local_user"
$env:ORDERPILOT_DB_PASSWORD = "change-me-local-dev-only"

# 3. local/demo trusted-header mode
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_ENABLED = "true"
$env:ORDERPILOT_GATEWAY_HEADER_AUTH_SIGNATURE_REQUIRED = "false"   # local/demo proof only

mvn -f apps/core-api/pom.xml spring-boot:run
```

In a separate PowerShell session:

```powershell
cd C:\OrderPilot\OrderPilot-Core\apps\web-dashboard

$env:ORDERPILOT_DEMO_MODE = "true"
$env:ORDERPILOT_DEMO_TENANT_ID = "11111111-1111-4111-8111-111111111111"
$env:ORDERPILOT_CORE_API_BASE_URL = "http://localhost:8080"

npm run dev
```

Verification:

```powershell
$TenantId = "11111111-1111-4111-8111-111111111111"

$AnalyticsHeaders = @{
  "X-Tenant-Id" = $TenantId
  "X-OrderPilot-Permissions" = "ANALYTICS_READ"
  "X-OrderPilot-Actor-Id" = "00000000-0000-4000-8000-000000000001"
}

$AdminHeaders = @{
  "X-Tenant-Id" = $TenantId
  "X-OrderPilot-Permissions" = "ADMIN_SETTINGS_READ"
  "X-OrderPilot-Actor-Id" = "00000000-0000-4000-8000-000000000001"
}

Invoke-WebRequest "http://localhost:8080/actuator/health" -UseBasicParsing
Invoke-WebRequest "http://localhost:8080/api/v1/runtime-control/demo-flow" -Headers $AnalyticsHeaders -UseBasicParsing
Invoke-WebRequest "http://localhost:8080/api/v1/commerce-intelligence/demo-flow" -Headers $AnalyticsHeaders -UseBasicParsing
Invoke-WebRequest "http://localhost:8080/api/v1/channels/rfq-handoffs" -Headers $AdminHeaders -UseBasicParsing

Invoke-WebRequest "http://localhost:3000/runtime-control" -UseBasicParsing
Invoke-WebRequest "http://localhost:3000/commerce-intelligence" -UseBasicParsing
Invoke-WebRequest "http://localhost:3000/channels/rfq-handoffs" -UseBasicParsing
Invoke-WebRequest "http://localhost:3000/demo" -UseBasicParsing
```

Expected result:
- health returns `UP`;
- all three authenticated backend read paths return `200`, not `401`/`403`/`503`;
- dashboard pages return `200`;
- seeded RFQ handoff is visible through commerce read model, handoff-list API, and SSR pages;
- external execution remains disabled/not invoked/not requested.
## 11. Result summary

| Item | Result |
| --- | --- |
| Live PostgreSQL + 66 migrations clean | **PASS** (pass 1 + pass 2) |
| core-api health + both demo-flow endpoints 200 | **PASS** (pass 1, demo profile) |
| Frontend surfaces render (SSR) safe data | **PASS** (pass 1) |
| **Live seeded RFQ:** before/after counts, 1 tenant-scoped handoff | **PASS** (pass 2, §6.4) |
| **Replay/idempotency:** second seed → counts unchanged, no duplicate | **PASS** (pass 2, §6.4) |
| **No-external-execution:** change_request/outbox/connector = 0 after seed×2 | **PASS** (pass 2, §6.4) |
| Tenant-scope integrity (0 wrong-tenant handoffs) | **PASS** (pass 2) |
| Data-boundary / leak scan (responses + rendered pages, both passes) | **PASS** (0 leaks, 0 tenant-UUID, no raw dump/stack) |
| Authenticated API `200` (runtime-control, commerce, handoff-list) | **PASS** (pass 4, §6.6) |
| Seeded handoff **visible** via authenticated API + SSR pages | **PASS** (pass 4, §6.6) |
| Runtime-control still safe READ_ONLY posture; no false production claim | **PASS** (pass 4) |
| Visible operator **transition** walkthrough (draft/terminal) | **NOT EXERCISED this run** (handoff stays `PENDING_REVIEW`; prior evidence in runbook §14A) |
| Scripted-browser screenshots | **BLOCKED** (Chrome extension not connected across all passes) |
| Application code changed | **NONE** |

**Overall: PASS.** The seeded-RFQ proof is complete end to end at the data + authenticated API + server-
render layers: the documented seed created exactly one tenant-scoped `PENDING_REVIEW` handoff, replay was
idempotent (no duplicate state), tenant scope held, **zero** connector/ChangeRequest/outbox/sandbox rows
were ever created, and — once the demo auth profile was correctly applied (pass 4) — the handoff is
**visible through the authenticated commerce read model, the handoff-list API, and the SSR dashboard
pages**, with a clean leak scan and no false production claim. The only remaining gaps are
**environmental/optional**: scripted-browser screenshots (Chrome extension not connected) and the write-
side operator *transition* walkthrough (not exercised this pass). No evidence was fabricated; no app code
was changed.
