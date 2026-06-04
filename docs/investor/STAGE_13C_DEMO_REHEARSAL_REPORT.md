# Stage 13C Demo Rehearsal Report

Status note: This document is historical/superseded for current-stage tracking. The canonical current-stage source is `docs/product/current-stage.md`, which points to `docs/product/STAGE_STATUS_RECONCILIATION.md`. Do not use this file alone to determine the active implementation stage.

## Executive Result

`INVESTOR_WALKTHROUGH_READY`

Stage 13C aligned the investor walkthrough around one controlled RFQ story:

`Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`

The demo is ready for investor rehearsal when the backend, dashboard, seeded tenant data, and AI worker test status are known before the session. The walkthrough remains honest: the bot routes the RFQ to operator review, quote review shows validation and substitute context, quote approval remains operator controlled, and external execution remains disabled.

## Demo Flow Summary

1. Open `/demo`.
2. Show the Steppe Logistics RFQ scenario and send the exact demo Telegram RFQ payload.
3. Confirm the bot classifies the message as RFQ-oriented and requires operator review.
4. Open `/bot-conversations` and show captured intent, policy decision, review handoff, and absence of unsafe action controls.
5. Open `/quote-review` and show validation status, substitute context, approval needs, audit timeline, and `externalExecution=DISABLED`.
6. Open `/quotes` and use the frozen defaults: `CUST-001`, `PAD-OE-04465`, `WH-ALM`, `2 EA`.
7. Show quote approval and internal conversion controls while explaining that ERP, 1C, connector, and Telegram outbound execution remain disabled.

## Inconsistencies Found And Fixed

- The investor story is now anchored to one RFQ payload instead of multiple similar RFQ examples.
- The `/demo` RFQ panel, quote defaults, and runbook wording now use the same product, quantity, unit, customer, and warehouse story.
- Demo language was aligned around review, validation, audit, and human approval rather than autonomous execution.
- Safety wording was made explicit on the demo surfaces: `externalExecution=DISABLED`, no ERP write, no bot quote approval, and no final order creation by bot.

## Files Changed

- `apps/web-dashboard/lib/demo-api.ts`
- `apps/web-dashboard/components/demo-dashboard.tsx`
- `apps/web-dashboard/components/bot-conversations-workspace.tsx`
- `apps/web-dashboard/tests/demo-dashboard.test.mjs`
- `docs/investor/STAGE_13B_INVESTOR_DEMO_SCRIPT.md`
- `docs/runbooks/STAGE_13B_DEMO_RUNBOOK.md`

## Verification Results

Stage 13C preserved the existing verification command set for final freeze rerun. Stage 13D reran the full set on 2026-06-01 with these results:

```powershell
cd apps/core-api; mvn test                                      # PASS
cd apps/web-dashboard; npm.cmd run lint                         # PASS
cd apps/web-dashboard; npm.cmd exec tsc -- --noEmit --incremental false # PASS
cd apps/web-dashboard; npm.cmd test                             # PASS
cd apps/web-dashboard; npm.cmd run build                        # PASS
cd apps/ai-worker; .\.venv\Scripts\python.exe -m pytest          # PASS
```

The first Maven attempt was blocked by sandboxed dependency resolution. The same command passed after Maven was allowed to resolve the missing Spring Boot parent dependency.

## Final Demo Limitations

- External ERP, 1C, connector, and outbound send execution are intentionally disabled.
- Quote approval is not autonomous.
- Substitute approval is not autonomous.
- Real Telegram outbound send is not enabled.
- Backend-backed screens require seeded tenant and environment IDs.
- Some technical IDs remain shortened because richer read models do not exist yet.
- Audit metadata is readable for the demo path but is not globally normalized across all historical event types.

## Recommendation

Proceed to Stage 13D freeze documentation and preflight. Treat the RFQ payload, seeded defaults, safety posture, and walkthrough route order as frozen unless a blocker is discovered during verification.
