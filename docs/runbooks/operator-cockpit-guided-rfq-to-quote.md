# Operator Cockpit v1 — Guided RFQ-to-Quote Flow (GitHub PR #260)

Product-forward operator walkthrough for the demo RFQ-to-quote path. This slice adds a **guided cockpit
journey** on top of the existing (PR #242/#244/#245/#252) RFQ handoff workspace so an operator can see the
whole flow at a glance and always know the next safe action. No new backend contract, no external execution.

```
/demo -> Send demo Telegram RFQ (seeds one PENDING_REVIEW handoff via the managed BFF)
     -> /channels/rfq-handoffs -> Open a handoff
     -> Guided RFQ-to-quote cockpit shows:
          source channel, detected intent, request text, handoff status,
          AI advisory status, draft quote status, draft line count,
          safe terminal state, audit status
     -> Next operator action is stated explicitly
     -> Safety posture: externalExecution=DISABLED, connector NOT_INVOKED, outbox NOT_REQUESTED
     -> Links back to /commerce-intelligence and /runtime-control
```

## What is new in this slice

- `apps/web-dashboard/components/rfq-cockpit-journey.tsx` — a read/display-only guided journey panel.
- Wired into `apps/web-dashboard/components/rfq-handoff-workspace.tsx` above the raw detail panel; it is
  driven only by state the workspace already holds (`detail`, `aiSuggestion`, `draftResult`,
  `decisionResult`). It renders no mutation control of its own.

The existing transitions (start review / dismiss / generate advisory / create draft quote / complete or
decline the safe demo terminal / close without draft) are unchanged and still owned by the backend.

## Honest, non-inflated state

Steps that have not happened yet render an explicit state token instead of a fake number or an implied
production commitment:

- AI advisory status: `NOT_GENERATED` until an advisory suggestion exists.
- Draft quote status: `NOT_CREATED` until a review-required draft is created.
- Draft line count: `NOT_MEASURED` until a draft exists (then the real `lines.length`).
- Safe terminal state: `NOT_RECORDED` until a demo decision is recorded.
- Detected intent / request text: `NOT_DETECTED` / `NOT_PROVIDED` when the handoff lacks them.

## Safety law preserved

- No ERP/1C/accounting write, connector command, connector sandbox execution, ChangeRequest execution,
  outbox external execution, or autonomous AI approval is reachable from this cockpit.
- The cockpit renders no client-owned authority fields (`tenantId`, `actorId`, `reviewerUserId`,
  `sourceExternalEventId`, `inboundChannelEventId`, `channelConnectionId`, status/execution authority) and
  no internal IDs, raw payloads, prompts, secrets, tokens, or stack traces.
- Tenant is header-borne (`X-Tenant-Id`); actor, permission, workflow state, audit, and idempotency are
  resolved by the backend on the unchanged transition endpoints.

## Manual walkthrough

1. Start core-api + web-dashboard with the demo tenant configured (see `LOCAL_DEMO_RUNBOOK.md`).
2. Open `/demo`, click **Send demo Telegram RFQ** (seeds one `PENDING_REVIEW` handoff).
3. Open `/channels/rfq-handoffs`, click **Open** on the pending handoff.
4. Confirm the **Guided RFQ-to-quote cockpit** panel shows the source channel, detected intent, request
   text, handoff status, and the `NOT_*` states for advisory/draft/terminal before any action.
5. Take the handoff into review, generate an advisory suggestion, create the draft quote, then complete or
   decline the safe demo terminal — watch each step flip from its `NOT_*` state to the real value.
6. Confirm the safety posture always reads `externalExecution=DISABLED` and follow the links to
   `/commerce-intelligence` and `/runtime-control`.

## Verification executed in this slice

```bash
cd apps/web-dashboard
node --test tests/rfq-cockpit-journey.test.mjs tests/rfq-handoffs.test.mjs   # 41/41 pass
npm test          # full frontend suite 493/493 pass
npm run typecheck # clean
npm run lint      # clean
```

## Not proven / deferred

- Live PostgreSQL + real-browser screenshot walkthrough of the guided cockpit was not executed in this
  environment. Source-inspection tests prove the source contract and forbidden-field absence (consistent
  with the existing RFQ tests); live browser rendering is not proven and remains deferred — see
  `docs/backlog/fix-notebook.md`.
- No backend action was added this slice. Any richer draft-quote inspection view or a first-class
  draft-quote detail route is deferred to a follow-up live operator transition proof slice (see fix-notebook).
