# Stage 13D Demo Preflight Checklist

Run this checklist before the investor walkthrough. The goal is repeatability, not new behavior.

## Environment

- [ ] Backend is running at the expected Core API URL, usually `http://localhost:8080`.
- [ ] Web dashboard is running at the expected dashboard URL, usually `http://localhost:3000`.
- [ ] AI worker test status is known. Use the latest result from `cd apps/ai-worker; .\.venv\Scripts\python.exe -m pytest`.
- [ ] Seeded tenant ID is configured in `NEXT_PUBLIC_DEMO_TENANT_ID`.
- [ ] Seeded product/location IDs are configured when reconciliation or backend-backed demo controls are shown.
- [ ] Browser is opened in a clean enough state that old demo results do not confuse the story.

## Demo Data

- [ ] Demo RFQ text is visible on `/demo` exactly as `Need 2 EA PAD-OE-04465 brake pads for Toyota Camry 2018, wholesale, Almaty.`
- [ ] `/quotes` defaults show customer `CUST-001`.
- [ ] `/quotes` defaults show product `PAD-OE-04465`.
- [ ] `/quotes` defaults show warehouse/location `WH-ALM`.
- [ ] `/quotes` defaults show quantity `2` and unit `EA`.

## Walkthrough Surfaces

- [ ] `/demo` loads and shows the Steppe Logistics RFQ flow.
- [ ] Sending the demo Telegram RFQ returns a visible result or a clear backend seed/config limitation.
- [ ] Bot review path is visible on `/demo` and `/bot-conversations`.
- [ ] `/bot-conversations` shows captured intent, policy decision, handoff/review state, and no unsafe controls.
- [ ] `/quote-review` loads the review queue or a clear seeded-data limitation.
- [ ] Quote review detail shows validation, substitute context, approval needs, and audit timeline when seeded data is present.
- [ ] `/quotes` can create or explain the draft quote path using the frozen defaults.

## Safety Gates

- [ ] `externalExecution=DISABLED` is visible on `/demo`.
- [ ] `externalExecution=DISABLED` is visible on `/quote-review`.
- [ ] Quote conversion messaging says external ERP write is disabled or not executed.
- [ ] No real Telegram outbound send is enabled.
- [ ] No ERP, 1C, warehouse, accounting, or external connector write is enabled.
- [ ] No autonomous quote approval is enabled.
- [ ] No autonomous substitute approval is enabled.

## Verification Commands

Preserve and run the full verification set for freeze signoff:

```powershell
cd apps/core-api; mvn test
cd apps/web-dashboard; npm.cmd run lint
cd apps/web-dashboard; npm.cmd exec tsc -- --noEmit --incremental false
cd apps/web-dashboard; npm.cmd test
cd apps/web-dashboard; npm.cmd run build
cd apps/ai-worker; .\.venv\Scripts\python.exe -m pytest
```

## Go / No-Go

- [ ] Go only if the frozen RFQ story is intact.
- [ ] Go only if external execution remains disabled.
- [ ] Go only if the presenter can explain any missing seed data as a local readiness issue, not a product claim.
